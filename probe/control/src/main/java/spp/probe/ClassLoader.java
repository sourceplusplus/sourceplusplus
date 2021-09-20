package spp.probe;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

public final class ClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    /*
     * Required when this classloader is used as the system classloader
     */
    public ClassLoader(java.lang.ClassLoader parent) {
        super(new URL[0], parent);
    }

    void add(URL url) {
        addURL(url);
    }

    public static ClassLoader findAncestor(java.lang.ClassLoader cl) {
        do {
            if (cl instanceof ClassLoader) {
                return (ClassLoader) cl;
            }
            cl = cl.getParent();
        } while (cl != null);
        return null;
    }

    /*
     *  Required for Java Agents when this classloader is used as the system classloader
     */
    @SuppressWarnings("unused")
    private void appendToClassPathForInstrumentation(String jarFile) throws IOException {
        add(Paths.get(jarFile).toRealPath().toUri().toURL());
    }
}
