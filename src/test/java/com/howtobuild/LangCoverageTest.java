package com.howtobuild;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolRegistry;

/** Every tool, parameter, option, section and role the GUI shows has an English name. */
class LangCoverageTest {
	private static Set<String> keys() throws IOException {
		Path lang = Path.of(System.getProperty("howtobuild.lang", "src/main/resources/assets/howtobuild/lang/en_us.json"));

		if (!Files.exists(lang)) lang = Path.of("/home/user/how-to-circle/src/main/resources/assets/howtobuild/lang/en_us.json");

		Set<String> keys = new TreeSet<>();
		Matcher m = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:", Pattern.MULTILINE).matcher(Files.readString(lang));

		while (m.find()) keys.add(m.group(1));

		return keys;
	}

	@Test
	void everythingShownHasATranslation() throws IOException {
		Set<String> keys = keys();
		Set<String> missing = new TreeSet<>();

		for (BuildTool tool : ToolRegistry.all()) {
			need(keys, missing, tool.translationKey());
			need(keys, missing, tool.translationKey() + ".desc");

			for (ToolParameter p : tool.parameters()) {
				need(keys, missing, p.labelKey());

				if (p.sectionKey() != null) need(keys, missing, "section.howtobuild." + p.sectionKey());

				if (p.type() == ToolParameter.Type.ENUM) {
					p.options().forEach(o -> need(keys, missing, ToolParameter.optionKey(o)));
				}
			}
		}

		for (MaterialRole role : MaterialRole.values()) {
			need(keys, missing, "role.howtobuild." + role.key());
		}

		assertTrue(missing.isEmpty(), "Missing translations: " + missing);
	}

	private static void need(Set<String> keys, Set<String> missing, String key) {
		if (!keys.contains(key)) missing.add(key);
	}
}
