public class A {
  public static int f(S s) {
    return switch (s) {
      case X x -> 1;
      case Y y -> 2;
    };
  }
}
