package com.example.mymod.qa;

public final class QaModifiers {
  private static boolean shift;
  private static boolean control;
  private static boolean alt;

  private QaModifiers() {}

  public static void shift(boolean held) {
    shift = held;
  }

  public static void control(boolean held) {
    control = held;
  }

  public static void alt(boolean held) {
    alt = held;
  }

  public static boolean isShift() {
    return shift;
  }

  public static boolean isControl() {
    return control;
  }

  public static boolean isAlt() {
    return alt;
  }

  public static boolean isHeld(int code) {
    return switch (code) {
      case 340, 344 -> shift;
      case 341, 345, 343, 347 -> control;
      case 342, 346 -> alt;
      default -> false;
    };
  }

  public static String describe() {
    return "shift=" + shift + " ctrl=" + control + " alt=" + alt;
  }
}
