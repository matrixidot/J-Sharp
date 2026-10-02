package dev.jsharp.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageInfoTest {
  @Test
  void versionIsInjectedByTheBuild() {
    assertThat(LanguageInfo.VERSION).isNotBlank().isNotEqualTo("unknown").doesNotContain("${");
    assertThat(LanguageInfo.FILE_EXTENSION).isEqualTo(".jsharp");
  }
}
