// javac warnings in Java sources are reported as JS1001 (and the build succeeds).
public class Old {
    public static strictfp double half(double x) { return x / 2; }         //~ JS1001
}
