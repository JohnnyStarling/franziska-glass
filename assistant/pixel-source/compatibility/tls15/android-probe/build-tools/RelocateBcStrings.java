package lab.franziska.tls15probe.build;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Rewrites BC package-name string constants that type relocation cannot see. */
public final class RelocateBcStrings {
    private static final String FROM = "org.bouncycastle";
    private static final String TO = "com.franziska.glass.compat.tls15.shaded";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.jar output.jar required");
        ZipInputStream input = new ZipInputStream(new FileInputStream(args[0]));
        ZipOutputStream output = new ZipOutputStream(new FileOutputStream(args[1]));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry replacement = new ZipEntry(entry.getName());
                replacement.setTime(entry.getTime());
                output.putNextEntry(replacement);
                if (entry.getName().endsWith(".class")) {
                    byte[] bytes = readAll(input, buffer);
                    output.write(rewriteClass(bytes));
                } else {
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
                output.closeEntry();
                input.closeEntry();
            }
        } finally {
            try { input.close(); } finally { output.close(); }
        }
    }

    private static byte[] rewriteClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            public FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                return super.visitField(access, name, descriptor, signature, rewrite(value));
            }

            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(rewrite(value));
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static Object rewrite(Object value) {
        if (!(value instanceof String)) return value;
        String text = (String) value;
        if (text.indexOf(FROM) < 0) return text;
        return text.replace(FROM, TO);
    }

    private static byte[] readAll(ZipInputStream input, byte[] buffer) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
