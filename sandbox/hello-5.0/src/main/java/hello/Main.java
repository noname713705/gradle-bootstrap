package hello;

public class Main {
    public static void main(String[] args) {
        // Implementation-Version in the jar manifest is set to the Gradle version that built the jar
        String builtWith = Main.class.getPackage().getImplementationVersion();
        System.out.println("Hello, built with Gradle " + (builtWith != null ? builtWith : "(unknown, not run from the jar)"));
    }
}
