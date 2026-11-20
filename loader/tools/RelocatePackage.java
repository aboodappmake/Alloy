import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Alloy adapter helper: moves a package inside one jar (a library a Fabric mod nests, e.g. ANTLR 4.13 in Iris) so it no
 * longer clashes with another version of the same library that Forge needs (ANTLR 4.9 for the access transformer parser).
 * Every class reference from/x/y is rewritten to to/from/x/y (prefix match on internal names, also inside string constants
 * that are exactly a class name in either dot or slash form). With --move the class files and service files under the
 * prefix are renamed too (the library jar itself); without it only the references change (the jars that use the library).
 * Signature files are dropped (the jar is changed).
 *
 * usage: java RelocatePackage <in.jar> <out.jar> <from/prefix/> <to/prefix/> [--move]   (prints "relocated N classes")
 */
public class RelocatePackage {
    public static void main(String[] a) throws Exception {
        Path in = Paths.get(a[0]), out = Paths.get(a[1]);
        String from = a[2], to = a[3];
        boolean move = a.length > 4 && a[4].equals("--move");
        String fromDot = from.replace('/', '.'), toDot = to.replace('/', '.');
        Remapper r = new Remapper() {
            @Override public String map(String name) {
                return name.startsWith(from) ? to + name.substring(from.length()) : name;
            }
            @Override public Object mapValue(Object v) {
                if (v instanceof String s) {
                    if (s.startsWith(from)) return to + s.substring(from.length());
                    if (s.startsWith(fromDot)) return toDot + s.substring(fromDot.length());
                }
                return super.mapValue(v);
            }
        };
        int n = 0;
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipFile z = new ZipFile(in.toFile()); ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                if (ze.isDirectory()) continue;
                String up = name.toUpperCase();
                if (up.startsWith("META-INF/") && (up.endsWith(".SF") || up.endsWith(".RSA") || up.endsWith(".DSA") || up.endsWith(".EC"))) continue;
                byte[] b;
                try (InputStream is = z.getInputStream(ze)) { b = is.readAllBytes(); }
                if (name.endsWith(".class")) {
                    ClassReader cr = new ClassReader(b);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, r), 0);
                    byte[] nb = cw.toByteArray();
                    if (move && name.startsWith(from)) { name = to + name.substring(from.length()); n++; }
                    else if (!java.util.Arrays.equals(nb, b)) n++;
                    b = nb;
                } else if (move && name.startsWith(from)) {
                    name = to + name.substring(from.length());
                } else if (name.startsWith("META-INF/services/")) {
                    String svc = name.substring("META-INF/services/".length());
                    if (svc.startsWith(fromDot)) name = "META-INF/services/" + toDot + svc.substring(fromDot.length());
                    b = new String(b, StandardCharsets.UTF_8).replace(fromDot, toDot).getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry o = new ZipEntry(name);
                o.setTime(ze.getTime());
                zo.putNextEntry(o);
                zo.write(b);
                zo.closeEntry();
            }
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("relocated " + n + " classes");
    }
}
