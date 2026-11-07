import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 去掉 class 文件里的 MethodParameters 属性。
 *
 * <p>为什么需要它：javac 21 给匿名内部类的构造器参数（编译器补的 outer this）会写一条
 * name_index = 0 的 MethodParameters 记录；本机 build-tools 里的 d8 8.2.2 在解析这种
 * 「无名字参数」时会抛 NullPointerException，导致整个 dex 生成失败。
 * MethodParameters 是可选调试属性，删掉不影响运行，dexer 也就不再踩到这个 bug。
 *
 * <p>用法：java StripMethodParameters <classesDir>
 */
public final class StripMethodParameters {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("用法: java StripMethodParameters <classesDir>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        }
        int stripped = 0;
        int changed = 0;
        for (Path file : files) {
            byte[] input = Files.readAllBytes(file);
            Result result = process(input);
            if (result.removed > 0) {
                Files.write(file, result.bytes);
                changed++;
                stripped += result.removed;
            }
        }
        System.out.println("StripMethodParameters: 处理 " + files.size() + " 个 class，"
                + "修改 " + changed + " 个文件，删除 " + stripped + " 条属性");
    }

    private static final class Result {
        byte[] bytes;
        int removed;
    }

    private static final class Reader {
        final byte[] data;
        int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int u1() {
            return data[pos++] & 0xFF;
        }

        int u2() {
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        int u4() {
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        void skip(int n) {
            pos += n;
        }
    }

    private static Result process(byte[] data) {
        Reader in = new Reader(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        Result result = new Result();

        // magic + version
        for (int i = 0; i < 8; i++) {
            out.write(data[i]);
        }
        in.pos = 8;

        int cpCount = in.u2();
        writeU2(out, cpCount);
        Map<Integer, String> utf8 = new HashMap<>();
        for (int i = 1; i < cpCount; i++) {
            int tag = in.u1();
            out.write(tag);
            switch (tag) {
                case 1: { // Utf8
                    int len = in.u2();
                    writeU2(out, len);
                    byte[] bytes = new byte[len];
                    System.arraycopy(data, in.pos, bytes, 0, len);
                    in.skip(len);
                    out.write(bytes, 0, len);
                    utf8.put(i, new String(bytes, StandardCharsets.UTF_8));
                    break;
                }
                case 7: // Class
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    copy(in, out, 2);
                    break;
                case 15: // MethodHandle
                    copy(in, out, 3);
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    copy(in, out, 4);
                    break;
                case 5: // Long
                case 6: // Double
                    copy(in, out, 8);
                    i++; // 占两个 cp 槽位
                    break;
                default:
                    throw new IllegalStateException("未知常量池 tag=" + tag + " @" + (in.pos - 1));
            }
        }

        // class 基本信息
        copy(in, out, 6); // access_flags, this_class, super_class
        int interfaces = in.u2();
        writeU2(out, interfaces);
        copy(in, out, interfaces * 2);

        // 字段：原样保留
        int fields = in.u2();
        writeU2(out, fields);
        for (int i = 0; i < fields; i++) {
            copy(in, out, 6); // access, name, descriptor
            copyAttributes(in, out, utf8, new int[]{0});
        }

        // 方法：过滤 MethodParameters
        int methods = in.u2();
        writeU2(out, methods);
        for (int i = 0; i < methods; i++) {
            copy(in, out, 6);
            int[] removed = new int[1];
            copyAttributes(in, out, utf8, removed);
            result.removed += removed[0];
        }

        // 类级属性：原样保留
        int classAttributes = in.u2();
        writeU2(out, classAttributes);
        for (int i = 0; i < classAttributes; i++) {
            int nameIndex = in.u2();
            int length = in.u4();
            writeU2(out, nameIndex);
            writeU4(out, length);
            copy(in, out, length);
        }

        result.bytes = out.toByteArray();
        return result;
    }

    private static void copyAttributes(Reader in, ByteArrayOutputStream out,
                                       Map<Integer, String> utf8, int[] removed) {
        int count = in.u2();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Reader bodyReader = in;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int nameIndex = bodyReader.u2();
            int length = bodyReader.u4();
            String name = utf8.get(nameIndex);
            int start = bodyReader.pos;
            bodyReader.skip(length);
            if ("MethodParameters".equals(name)) {
                removed[0]++;
                continue;
            }
            writeU2(body, nameIndex);
            writeU4(body, length);
            body.write(bodyReader.data, start, length);
            kept++;
        }
        writeU2(out, kept);
        byte[] bytes = body.toByteArray();
        out.write(bytes, 0, bytes.length);
    }

    private static void copy(Reader in, ByteArrayOutputStream out, int n) {
        out.write(in.data, in.pos, n);
        in.pos += n;
    }

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private StripMethodParameters() {
    }


}
