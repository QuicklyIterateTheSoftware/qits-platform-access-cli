package eu.wohlben.qits.cli.access.publish;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The two archive formats this CLI writes and reads, written deterministically: the same entries
 * give the same bytes, run after run and machine after machine. Nothing here reads a clock, an
 * owner, a permission or a directory order.
 *
 * <p>A jar entry carries {@code 1980-01-01T00:00} local time (the DOS epoch, so no extended-time
 * extra field is written) and no extra fields. A tar entry carries mode {@code 0644}, uid and gid 0,
 * empty owner names and mtime {@code 499162500} (1985-10-26T08:15:00Z, npm's reproducible-pack
 * constant).
 */
final class Archives {

    /** One archive entry. A directory's name ends in {@code /} and its bytes are empty. */
    record Entry(String name, byte[] bytes) {

        boolean directory() {
            return name.endsWith("/");
        }
    }

    static final LocalDateTime JAR_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    static final long TAR_MTIME = 499162500L;

    private Archives() {
    }

    // --- jar -------------------------------------------------------------------------------------

    /** Every entry of a jar, in archive order. */
    static List<Entry> readJar(byte[] jar, String what) {
        List<Entry> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.add(new Entry(entry.getName(), entry.isDirectory() ? new byte[0] : in.readAllBytes()));
            }
        } catch (IOException e) {
            throw CliException.policy(what + " is not a readable jar: " + e.getMessage());
        }
        return entries;
    }

    /**
     * A jar of these entries, sorted by name. {@code stored} writes them uncompressed (the contract
     * jars, which are small JSON), otherwise DEFLATED at level 9 (a bundled jar).
     */
    static byte[] writeJar(List<Entry> entries, boolean stored) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> HashManifest.UTF8_ORDER.compare(a.name(), b.name()));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            out.setLevel(Deflater.BEST_COMPRESSION);
            for (Entry entry : sorted) {
                ZipEntry zip = new ZipEntry(entry.name());
                zip.setTimeLocal(JAR_TIME);
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.bytes());
                    zip.setMethod(ZipEntry.STORED);
                    zip.setSize(entry.bytes().length);
                    zip.setCompressedSize(entry.bytes().length);
                    zip.setCrc(crc.getValue());
                } else {
                    zip.setMethod(ZipEntry.DEFLATED);
                }
                out.putNextEntry(zip);
                out.write(entry.bytes());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("writing a jar in memory failed", e);
        }
        return buffer.toByteArray();
    }

    // --- tar.gz ----------------------------------------------------------------------------------

    /** A gzipped ustar archive of these regular files, sorted by the UTF-8 bytes of their names. */
    static byte[] writeTarGz(List<Entry> files) {
        List<Entry> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> HashManifest.UTF8_ORDER.compare(a.name(), b.name()));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            for (Entry file : sorted) {
                gzip.write(header(file.name(), file.bytes().length));
                gzip.write(file.bytes());
                int pad = (512 - file.bytes().length % 512) % 512;
                gzip.write(new byte[pad]);
            }
            gzip.write(new byte[1024]);
        } catch (IOException e) {
            throw new IllegalStateException("writing a tarball in memory failed", e);
        }
        return buffer.toByteArray();
    }

    /** Every regular file of a gzipped tar, in archive order. */
    static List<Entry> readTarGz(byte[] tarball, String what) {
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(tarball))) {
            byte[] header = new byte[512];
            while (in.readNBytes(header, 0, 512) == 512) {
                if (isZero(header)) {
                    break;
                }
                String name = field(header, 0, 100);
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                String sizeField = field(header, 124, 12).trim();
                long size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField, 8);
                byte type = header[156];
                byte[] bytes = in.readNBytes((int) size);
                in.readNBytes((int) ((512 - size % 512) % 512));
                if (type == '0' || type == 0) {
                    entries.add(new Entry(name, bytes));
                }
            }
        } catch (IOException | NumberFormatException e) {
            throw CliException.policy(what + " is not a readable tarball: " + e.getMessage());
        }
        return entries;
    }

    private static byte[] header(String name, long size) {
        byte[] header = new byte[512];
        byte[] full = name.getBytes(StandardCharsets.UTF_8);
        String prefix = "";
        String shortName = name;
        if (full.length > 100) {
            // ustar's split: a prefix of up to 155 bytes, a slash, and a name of up to 100.
            int split = -1;
            for (int i = name.length() - 1; i > 0; i--) {
                if (name.charAt(i) == '/'
                        && name.substring(0, i).getBytes(StandardCharsets.UTF_8).length <= 155
                        && name.substring(i + 1).getBytes(StandardCharsets.UTF_8).length <= 100) {
                    split = i;
                    break;
                }
            }
            if (split < 0) {
                throw CliException.policy("the path '" + name + "' is too long for a tarball entry");
            }
            prefix = name.substring(0, split);
            shortName = name.substring(split + 1);
        }
        put(header, 0, 100, shortName.getBytes(StandardCharsets.UTF_8));
        put(header, 100, 8, octal(0644, 7));
        put(header, 108, 8, octal(0, 7));
        put(header, 116, 8, octal(0, 7));
        put(header, 124, 12, octal(size, 11));
        put(header, 136, 12, octal(TAR_MTIME, 11));
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';
        put(header, 257, 6, "ustar\0".getBytes(StandardCharsets.US_ASCII));
        put(header, 263, 2, "00".getBytes(StandardCharsets.US_ASCII));
        put(header, 329, 8, octal(0, 7));
        put(header, 337, 8, octal(0, 7));
        put(header, 345, 155, prefix.getBytes(StandardCharsets.UTF_8));
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        byte[] checksum = (String.format("%06o", sum) + "\0 ").getBytes(StandardCharsets.US_ASCII);
        put(header, 148, 8, checksum);
        return header;
    }

    private static byte[] octal(long value, int digits) {
        return (String.format("%0" + digits + "o", value) + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static void put(byte[] header, int offset, int length, byte[] value) {
        System.arraycopy(value, 0, header, offset, Math.min(length, value.length));
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
