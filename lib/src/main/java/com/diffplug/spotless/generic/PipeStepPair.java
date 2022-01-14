/*
 * Copyright 2020-2022 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.spotless.generic;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.FormatterFunc;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Lint;

public class PipeStepPair {
	/** Declares the name of the step. */
	public static PipeStepPair named(String name) {
		return new PipeStepPair(name);
	}

	public static String defaultToggleName() {
		return "toggle";
	}

	public static String defaultToggleOff() {
		return "spotless:off";
	}

	public static String defaultToggleOn() {
		return "spotless:on";
	}

	String name;
	Pattern regex;

	private PipeStepPair(String name) {
		this.name = Objects.requireNonNull(name);
	}

	/** Defines the opening and closing markers. */
	public PipeStepPair openClose(String open, String close) {
		return regex(Pattern.quote(open) + "([\\s\\S]*?)" + Pattern.quote(close));
	}

	/** Defines the pipe via regex. Must have *exactly one* capturing group. */
	public PipeStepPair regex(String regex) {
		return regex(Pattern.compile(regex));
	}

	/** Defines the pipe via regex. Must have *exactly one* capturing group. */
	public PipeStepPair regex(Pattern regex) {
		this.regex = Objects.requireNonNull(regex);
		return this;
	}

	private void assertRegexSet() {
		Objects.requireNonNull(regex, "must call regex() or openClose()");
	}

	/** Returns a step which will apply the given steps but preserve the content selected by the regex / openClose pair. */
	public FormatterStep preserveWithin(Path rootPath, List<FormatterStep> steps) {
		assertRegexSet();
		return FormatterStep.createLazy(name,
				() -> new PreserveWithin(regex, steps),
				state -> FormatterFunc.Closeable.of(state.buildFormatter(rootPath), state));
	}

	/**
	 * Returns a step which will apply the given steps only within the blocks selected by the regex / openClose pair.
	 * Linting within the substeps is not supported.
	 */
	public FormatterStep applyWithin(Path rootPath, List<FormatterStep> steps) {
		assertRegexSet();
		return FormatterStep.createLazy(name,
				() -> new ApplyWithin(regex, steps),
				state -> FormatterFunc.Closeable.of(state.buildFormatter(rootPath), state));
	}

	static class ApplyWithin extends Apply implements FormatterFunc.Closeable.ResourceFuncNeedsFile<Formatter> {
		ApplyWithin(Pattern regex, List<FormatterStep> steps) {
			super(regex, steps);
		}

		@Override
		public String apply(Formatter formatter, String unix, File file) throws Exception {
			List<String> groups = groupsZeroed();
			Matcher matcher = regex.matcher(unix);
			while (matcher.find()) {
				// apply the formatter to each group
				groups.add(formatter.compute(matcher.group(1), file));
			}
			// and then assemble the result right away
			return assembleGroups(unix);
		}
	}

	static class PreserveWithin extends Apply implements FormatterFunc.Closeable.ResourceFuncNeedsFile<Formatter> {
		PreserveWithin(Pattern regex, List<FormatterStep> steps) {
			super(regex, steps);
		}

		private void storeGroups(String unix) {
			List<String> groups = groupsZeroed();
			Matcher matcher = regex.matcher(unix);
			while (matcher.find()) {
				// store whatever is within the open/close tags
				groups.add(matcher.group(1));
			}
		}

		@Override
		public String apply(Formatter formatter, String unix, File file) throws Exception {
			storeGroups(unix);
			String formatted = formatter.compute(unix, file);
			return assembleGroups(formatted);
		}

		@Override
		public List<Lint> lint(Formatter formatter, String content, File file) throws Exception {
			// first make sure that all tags are preserved, and bail if they aren't
			try {
				apply(formatter, content, file);
			} catch (IntermediateStepRemovedException e) {
				return Collections.singletonList(e.lint);
			}
			// because the tags are preserved, now we can let the underlying lints run
			return formatter.lint(content, file);
		}
	}

	static class Apply implements Serializable {
		final Pattern regex;
		final List<FormatterStep> steps;

		transient ArrayList<String> groups = new ArrayList<>();
		transient StringBuilder builderInternal;

		public Apply(Pattern regex, List<FormatterStep> steps) {
			this.regex = regex;
			this.steps = steps;
		}

		protected ArrayList<String> groupsZeroed() {
			if (groups == null) {
				groups = new ArrayList<>();
			} else {
				groups.clear();
			}
			return groups;
		}

		private StringBuilder builderZeroed() {
			if (builderInternal == null) {
				builderInternal = new StringBuilder();
			} else {
				builderInternal.setLength(0);
			}
			return builderInternal;
		}

		protected Formatter buildFormatter(Path rootDir) {
			return Formatter.builder()
					.encoding(StandardCharsets.UTF_8) // can be any UTF, doesn't matter
					.lineEndingsPolicy(LineEnding.UNIX.createPolicy()) // just internal, won't conflict with user
					.steps(steps)
					.rootDir(rootDir)
					.build();
		}

		protected String assembleGroups(String unix) throws IntermediateStepRemovedException {
			if (groups.isEmpty()) {
				return unix;
			}
			StringBuilder builder = builderZeroed();
			Matcher matcher = regex.matcher(unix);
			int lastEnd = 0;
			int groupIdx = 0;
			while (matcher.find()) {
				builder.append(unix, lastEnd, matcher.start(1));
				builder.append(groups.get(groupIdx));
				lastEnd = matcher.end(1);
				++groupIdx;
			}
			if (groupIdx == groups.size()) {
				builder.append(unix, lastEnd, unix.length());
				return builder.toString();
			} else {
				int startLine = 1 + (int) builder.toString().codePoints().filter(c -> c == '\n').count();
				int endLine = 1 + (int) unix.codePoints().filter(c -> c == '\n').count();

				// throw an error with either the full regex, or the nicer open/close pair
				Matcher openClose = Pattern.compile("\\\\Q([\\s\\S]*?)\\\\E" + "\\Q([\\s\\S]*?)\\E" + "\\\\Q([\\s\\S]*?)\\\\E")
						.matcher(regex.pattern());
				String pattern;
				if (openClose.matches()) {
					pattern = openClose.group(1) + " " + openClose.group(2);
				} else {
					pattern = regex.pattern();
				}
				throw new IntermediateStepRemovedException(Lint.create("toggleOffOnRemoved",
						"An intermediate step removed a match of " + pattern,
						startLine, endLine));
			}
		}
	}

	static class IntermediateStepRemovedException extends Exception {
		Lint lint;

		IntermediateStepRemovedException(Lint lint) {
			this.lint = lint;
		}
	}
}
