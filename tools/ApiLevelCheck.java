import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 静态检查 DEX 里用到的 Android API 是否都在 minSdk 之上可用。
 *
 * <p>做法：解析 classes.dex 的方法/字段/父类引用，和 SDK 自带的
 * {@code platforms/android-34/data/api-versions.xml} 对照。没有真机时，
 * 这是发现 NoSuchMethodError / NoClassDefFoundError 最直接的办法。
 *
 * <p>用法：java ApiLevelCheck <apk> <api-versions.xml> <minSdk>
 */
public final class ApiLevelCheck {

    private static final Map<String, Integer> classes = new HashMap<>();
    private static final Map<String, Integer> methods = new HashMap<>();
    private static final Map<String, Integer> fields = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: java ApiLevelCheck <apk> <api-versions.xml> <minSdk>");
            System.exit(2);
        }
        int minApi = Integer.parseInt(args[2]);
        loadApi(args[1]);

        byte[] dex = readDex(args[0]);
        Dex d = new Dex(dex);

        List<String> violations = new ArrayList<>();
        Map<String, Integer> violationApi = new HashMap<>();

        // 1. 方法引用
        for (int i = 0; i < d.methodIdsSize; i++) {
            int off = d.methodIdsOff + i * 8;
            int classIdx = u2(dex, off);
            int protoIdx = u2(dex, off + 2);
            int nameIdx = u4(dex, off + 4);
            String owner = d.typeName(classIdx);
            if (!interesting(owner)) {
                continue;
            }
            String sig = d.methodSignature(protoIdx, nameIdx);
            Integer since = lookupMethod(owner, sig);
            if (since == null) {
                continue; // 自定义类/非公开 API，另一路径报告
            }
            if (since > minApi) {
                violations.add(owner + "->" + sig + " 需要 API " + since);
                bump(violationApi, owner + "->" + sig, since);
            }
        }

        // 2. 字段引用
        for (int i = 0; i < d.fieldIdsSize; i++) {
            int off = d.fieldIdsOff + i * 8;
            int classIdx = u2(dex, off);
            int typeIdx = u2(dex, off + 2);
            int nameIdx = u4(dex, off + 4);
            String owner = d.typeName(classIdx);
            if (!interesting(owner)) {
                continue;
            }
            String name = d.strings[nameIdx];
            Integer since = fields.get(owner + "#" + name);
            if (since == null) {
                Integer classSince = classes.get(owner);
                if (classSince == null) {
                    continue;
                }
                // 字段在表里没有记录（例如继承来的常量），跳过
                continue;
            }
            if (since > minApi) {
                violations.add(owner + "." + name + " 需要 API " + since);
                bump(violationApi, owner + "." + name, since);
            }
        }

        // 3. 我们自己的类继承/实现的上层类
        for (int i = 0; i < d.classDefsSize; i++) {
            int off = d.classDefsOff + i * 32;
            int classIdx = u4(dex, off);
            int superIdx = u4(dex, off + 8);
            int interfacesOff = u4(dex, off + 12);
            String self = d.typeName(classIdx);
            if (superIdx != -1) {
                checkClass(d.typeName(superIdx), self, minApi, violations);
            }
            if (interfacesOff != 0) {
                int size = u4(dex, interfacesOff);
                for (int k = 0; k < size; k++) {
                    int typeIdx = u2(dex, interfacesOff + 4 + k * 2);
                    checkClass(d.typeName(typeIdx), self, minApi, violations);
                }
            }
        }

        // 4. 完全找不到的 android/* 类（可能是隐藏 API）
        TreeSet<String> unknownClasses = new TreeSet<>();
        for (int i = 0; i < d.typeIdsSize; i++) {
            String name = d.typeName(i);
            if (interesting(name) && !classes.containsKey(name) && !name.startsWith("java/")) {
                unknownClasses.add(name);
            }
        }

        Collections.sort(violations, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareTo(b);
            }
        });

        System.out.println("minSdk = " + minApi + "，检查方法引用 " + d.methodIdsSize
                + " 个，字段引用 " + d.fieldIdsSize + " 个，类 " + d.classDefsSize + " 个");
        if (!violations.isEmpty()) {
            System.out.println();
            System.out.println("!! 超出 minSdk 的 API 调用（需要加版本判断或换写法）：");
            for (String v : dedupe(violations)) {
                System.out.println("   - " + v);
            }
        }
        if (!unknownClasses.isEmpty()) {
            System.out.println();
            System.out.println("?? 在 API 表中找不到的 android 类（可能是隐藏 API）：");
            for (String c : unknownClasses) {
                System.out.println("   - " + c);
            }
        }
        if (violations.isEmpty() && unknownClasses.isEmpty()) {
            System.out.println("OK：所有 Android API 调用都在 minSdk 之上可用。");
        }
        // 只报告，不用退出码卡构建；由人工确认
    }

    private static List<String> dedupe(List<String> list) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String s : list) {
            seen.put(s, Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }

    private static void bump(Map<String, Integer> map, String key, int since) {
        map.put(key, since);
    }

    private static void checkClass(String name, String owner, int minApi, List<String> violations) {
        Integer since = classes.get(name);
        if (since != null && since > minApi) {
            violations.add(owner + " 继承 " + name + "（API " + since + "）");
        }
    }

    private static boolean interesting(String name) {
        return name.startsWith("android/") || name.startsWith("androidx/")
                || name.startsWith("java/");
    }

    private static Integer lookupMethod(String owner, String sig) {
        Integer direct = methods.get(owner + "#" + sig);
        if (direct != null) {
            return direct;
        }
        Integer classSince = classes.get(owner);
        if (classSince != null) {
            // 方法在表里没有（继承而来），用类的 since 兜底
            return classSince;
        }
        return null;
    }

    // ------------------------------------------------------------ api-versions.xml

    private static void loadApi(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(path));
        NodeList classNodes = doc.getElementsByTagName("class");
        for (int i = 0; i < classNodes.getLength(); i++) {
            Element el = (Element) classNodes.item(i);
            String name = el.getAttribute("name");
            int classSince = intAttr(el, "since", 1);
            classes.put(name, classSince);
            NodeList children = el.getChildNodes();
            for (int k = 0; k < children.getLength(); k++) {
                Node child = children.item(k);
                if (!(child instanceof Element)) {
                    continue;
                }
                Element c = (Element) child;
                String tag = c.getTagName();
                if ("method".equals(tag)) {
                    int since = intAttr(c, "since", classSince);
                    methods.put(name + "#" + c.getAttribute("name"), since);
                } else if ("field".equals(tag)) {
                    int since = intAttr(c, "since", classSince);
                    fields.put(name + "#" + c.getAttribute("name"), since);
                }
            }
        }
    }

    private static int intAttr(Element el, String name, int fallback) {
        String value = el.getAttribute(name);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ DEX

    private static byte[] readDex(String apkPath) throws Exception {
        ZipFile zip = new ZipFile(apkPath);
        try {
            ZipEntry entry = zip.getEntry("classes.dex");
            if (entry == null) {
                throw new IllegalStateException("APK 里没有 classes.dex");
            }
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) entry.getSize());
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            in.close();
            return out.toByteArray();
        } finally {
            zip.close();
        }
    }

    private static int u2(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u4(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static final class Dex {
        final byte[] data;
        final String[] strings;
        final int stringIdsSize;
        final int stringIdsOff;
        final int typeIdsSize;
        final int typeIdsOff;
        final int protoIdsSize;
        final int protoIdsOff;
        final int fieldIdsSize;
        final int fieldIdsOff;
        final int methodIdsSize;
        final int methodIdsOff;
        final int classDefsSize;
        final int classDefsOff;

        Dex(byte[] data) {
            this.data = data;
            if (data.length < 112 || data[0] != 'd' || data[1] != 'e' || data[2] != 'x') {
                throw new IllegalStateException("不是合法的 dex 文件");
            }
            stringIdsSize = u4(data, 0x38);
            stringIdsOff = u4(data, 0x3C);
            typeIdsSize = u4(data, 0x40);
            typeIdsOff = u4(data, 0x44);
            protoIdsSize = u4(data, 0x48);
            protoIdsOff = u4(data, 0x4C);
            fieldIdsSize = u4(data, 0x50);
            fieldIdsOff = u4(data, 0x54);
            methodIdsSize = u4(data, 0x58);
            methodIdsOff = u4(data, 0x5C);
            classDefsSize = u4(data, 0x60);
            classDefsOff = u4(data, 0x64);

            strings = new String[stringIdsSize];
            for (int i = 0; i < stringIdsSize; i++) {
                int offset = u4(data, stringIdsOff + i * 4);
                strings[i] = readString(offset);
            }
        }

        private String readString(int offset) {
            int pos = offset;
            // uleb128 长度前缀
            while ((data[pos] & 0x80) != 0) {
                pos++;
            }
            pos++;
            int start = pos;
            while (pos < data.length && data[pos] != 0) {
                pos++;
            }
            return new String(data, start, pos - start, java.nio.charset.StandardCharsets.UTF_8);
        }

        String typeName(int typeIdx) {
            int stringIdx = u4(data, typeIdsOff + typeIdx * 4);
            String desc = strings[stringIdx];
            if (desc.startsWith("L") && desc.endsWith(";")) {
                return desc.substring(1, desc.length() - 1);
            }
            return desc;
        }

        String typeDescriptor(int typeIdx) {
            return strings[u4(data, typeIdsOff + typeIdx * 4)];
        }

        String methodSignature(int protoIdx, int nameIdx) {
            int off = protoIdsOff + protoIdx * 12;
            int returnTypeIdx = u4(data, off + 4);
            int paramsOff = u4(data, off + 8);
            StringBuilder sb = new StringBuilder(strings[nameIdx]);
            sb.append('(');
            if (paramsOff != 0) {
                int size = u4(data, paramsOff);
                for (int i = 0; i < size; i++) {
                    int typeIdx = u2(data, paramsOff + 4 + i * 2);
                    sb.append(typeDescriptor(typeIdx));
                }
            }
            sb.append(')');
            sb.append(typeDescriptor(returnTypeIdx));
            return sb.toString();
        }
    }

    private ApiLevelCheck() {
    }
}
