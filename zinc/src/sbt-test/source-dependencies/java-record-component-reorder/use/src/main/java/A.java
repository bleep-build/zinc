public class A {
  public static int f(Object o) {
    if (o instanceof R(int a, int b)) return a - b;
    return 0;
  }
}
