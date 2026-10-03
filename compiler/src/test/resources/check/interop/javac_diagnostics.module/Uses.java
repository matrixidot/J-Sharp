// javac errors in Java sources are reported as JS1000.
public class Uses {
    public static int ok() { return Lib.answer(); }
    public static int missing() { return Lib.nope(); }                     //~ JS1000
}
