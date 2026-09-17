package com.example.mymod.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class QaLog {
  private static Path out;

  private QaLog() {}

  public static void to(Path path) {
    out = path;
  }

  public static void say(String message) {
    System.out.println("[QA] " + message);
    if (out == null) {
      return;
    }
    try {
      Files.writeString(
          out,
          message + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException failure) {
      System.out.println("[QA] could not write the log: " + failure);
    }
  }
}
