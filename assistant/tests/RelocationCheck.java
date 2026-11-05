import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-only regression for BC's actual reflective deserialization allowlist. */
public final class RelocationCheck {
    public static void main(String[] args) throws Exception {
        String ns = "com.franziska.glass.compat.tls15.shaded.crypto.signers.xmss.";
        Class<?> nodeClass = Class.forName(ns + "XMSSNode");
        Constructor<?> constructor = nodeClass.getDeclaredConstructor(int.class, byte[].class);
        constructor.setAccessible(true);
        Object node = constructor.newInstance(0, new byte[32]);
        List<Object> nodes = new ArrayList<Object>();
        nodes.add(node);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ObjectOutputStream stream = new ObjectOutputStream(output);
        stream.writeObject(nodes);
        stream.close();
        Class<?> util = Class.forName(ns + "XMSSUtil");
        Method deserialize = util.getDeclaredMethod("deserialize", byte[].class, Class.class);
        deserialize.setAccessible(true);
        Object result = deserialize.invoke(null, output.toByteArray(), ArrayList.class);
        if (!nodeClass.isInstance(((List<?>) result).get(0))) throw new AssertionError("node type changed");
        Class<?> checking = Class.forName(ns + "XMSSUtil$CheckingStream");
        Field components = checking.getDeclaredField("components");
        components.setAccessible(true);
        for (Object name : (Set<?>) components.get(null)) Class.forName((String) name);
        Field aliases = checking.getDeclaredField("legacyNames");
        aliases.setAccessible(true);
        for (Map.Entry<?,?> entry : ((Map<?,?>) aliases.get(null)).entrySet()) {
            if (!entry.getKey().toString().startsWith("com.franziska.glass.compat.tls15.shaded.pqc.crypto.xmss."))
                throw new AssertionError("wrong legacy alias namespace");
            if (!((Class<?>) entry.getValue()).getName().startsWith(ns))
                throw new AssertionError("wrong legacy alias target");
        }
        System.out.println("PASS relocated XMSS whitelist, reflection roundtrip and legacy alias targets");
    }
}
