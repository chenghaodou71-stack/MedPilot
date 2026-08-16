package com.medpilot.attachment;

import com.medpilot.security.DataEncryptionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConsultationAttachmentStorage {

    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_DRAFT_CHARS = 4000;
    private static final int MAX_PDF_PAGES = 30;
    private static final String STORAGE_KEY_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin$";

    private final Path root;
    private final DataEncryptionService encryption;

    public ConsultationAttachmentStorage(
            @Value("${medpilot.attachments.storage-dir:./var/private/attachments}") String storageDir,
            DataEncryptionService encryption) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
        this.encryption = encryption;
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new AttachmentStorageException("attachment storage is unavailable", exception);
        }
    }

    public StoredAttachment store(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("Attachment must not be empty");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw new AttachmentTooLargeException();
        }

        String originalFilename = safeFilename(upload.getOriginalFilename());
        MediaDescriptor descriptor = MediaDescriptor.resolve(originalFilename, upload.getContentType());
        Path temporary = null;
        Path encryptedPath = null;
        try {
            temporary = Files.createTempFile(root, ".incoming-", ".tmp");
            DigestAndSize digest = copyAndDigest(upload, temporary);
            if (digest.size() > MAX_FILE_BYTES) {
                throw new AttachmentTooLargeException();
            }
            byte[] prefix = readPrefix(temporary, descriptor.prefixLength());
            if (!descriptor.matches(prefix)) {
                throw new IllegalArgumentException("File content does not match its declared type");
            }

            String extractedText = extractText(temporary, descriptor);
            String storageKey = UUID.randomUUID() + ".bin";
            encryptedPath = resolveStorageKey(storageKey);
            try (InputStream plaintext = Files.newInputStream(temporary)) {
                // The private store contains authenticated ciphertext, never the uploaded bytes.
                try (var destination = Files.newOutputStream(encryptedPath)) {
                    encryption.encryptStream(plaintext, destination);
                }
            }
            return new StoredAttachment(
                    storageKey,
                    originalFilename,
                    descriptor.mediaType(),
                    digest.size(),
                    digest.sha256(),
                    descriptor.kind(),
                    extractedText);
        } catch (AttachmentTooLargeException | IllegalArgumentException exception) {
            deleteQuietly(temporary);
            deleteQuietly(encryptedPath);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(temporary);
            deleteQuietly(encryptedPath);
            if (exception instanceof AttachmentStorageException storageException) {
                throw storageException;
            }
            throw new AttachmentStorageException("attachment could not be stored", exception);
        } finally {
            deleteQuietly(temporary);
        }
    }

    public void delete(String storageKey) {
        Path path = resolveStorageKey(storageKey);
        deleteQuietly(path);
    }

    Path resolveStorageKey(String storageKey) {
        if (storageKey == null || !storageKey.matches(STORAGE_KEY_PATTERN)) {
            throw new IllegalArgumentException("Invalid attachment storage key");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.getParent().equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid attachment storage path");
        }
        return resolved;
    }

    Path root() {
        return root;
    }

    private DigestAndSize copyAndDigest(MultipartFile upload, Path destination) throws IOException {
        try (InputStream input = new BufferedInputStream(upload.getInputStream());
             var output = Files.newOutputStream(destination)) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > MAX_FILE_BYTES) {
                    throw new AttachmentTooLargeException();
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            return new DigestAndSize(size, HexFormat.of().formatHex(digest.digest()));
        }
    }

    private byte[] readPrefix(Path file, int length) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return input.readNBytes(length);
        }
    }

    private String extractText(Path file, MediaDescriptor descriptor) throws IOException {
        if (descriptor.kind() != AttachmentKind.TEXT) {
            return "";
        }
        if (descriptor.pdf()) {
            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(Math.min(MAX_PDF_PAGES, document.getNumberOfPages()));
                return limitText(stripper.getText(document));
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return limitText(text);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Text attachments must use UTF-8", exception);
        }
    }

    private String limitText(String text) {
        if (text == null) return "";
        String normalized = text.replace("\u0000", "").strip();
        return normalized.length() <= MAX_DRAFT_CHARS
                ? normalized
                : normalized.substring(0, MAX_DRAFT_CHARS) + "\n[文本已截断]";
    }

    private static String safeFilename(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            throw new IllegalArgumentException("Attachment filename is required");
        }
        String normalized = submitted.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (basename.isBlank() || basename.length() > 160 || basename.equals(".") || basename.equals("..")) {
            throw new IllegalArgumentException("Invalid attachment filename");
        }
        return basename;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Orphan cleanup is handled by the retention job; do not leak the upload error.
        }
    }

    public record StoredAttachment(
            String storageKey,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            String sha256,
            AttachmentKind kind,
            String extractedText) {
    }

    private record DigestAndSize(long size, String sha256) {
    }

    private record MediaDescriptor(
            String extension,
            String mediaType,
            AttachmentKind kind,
            boolean pdf,
            int prefixLength) {

        private static MediaDescriptor resolve(String filename, String contentType) {
            String extension = extension(filename);
            String normalizedType = contentType == null
                    ? ""
                    : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            return switch (extension) {
                case ".txt" -> requireType(normalizedType, "text/plain",
                        new MediaDescriptor(extension, "text/plain", AttachmentKind.TEXT, false, 1));
                case ".pdf" -> requireType(normalizedType, "application/pdf",
                        new MediaDescriptor(extension, "application/pdf", AttachmentKind.TEXT, true, 5));
                case ".jpg", ".jpeg" -> requireType(normalizedType, "image/jpeg",
                        new MediaDescriptor(extension, "image/jpeg", AttachmentKind.IMAGE, false, 3));
                case ".png" -> requireType(normalizedType, "image/png",
                        new MediaDescriptor(extension, "image/png", AttachmentKind.IMAGE, false, 8));
                case ".webp" -> requireType(normalizedType, "image/webp",
                        new MediaDescriptor(extension, "image/webp", AttachmentKind.IMAGE, false, 12));
                case ".mp3" -> requireType(normalizedType, "audio/mpeg",
                        new MediaDescriptor(extension, "audio/mpeg", AttachmentKind.AUDIO, false, 3));
                case ".wav" -> requireTypeAny(normalizedType,
                        new String[] {"audio/wav", "audio/x-wav"},
                        new MediaDescriptor(extension, "audio/wav", AttachmentKind.AUDIO, false, 12));
                case ".m4a" -> requireTypeAny(normalizedType,
                        new String[] {"audio/mp4", "audio/x-m4a"},
                        new MediaDescriptor(extension, "audio/mp4", AttachmentKind.AUDIO, false, 12));
                default -> throw new IllegalArgumentException("Unsupported attachment type");
            };
        }

        private boolean matches(byte[] prefix) {
            return switch (extension) {
                case ".txt" -> prefix.length > 0 && !containsNul(prefix);
                case ".pdf" -> startsWith(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII));
                case ".jpg", ".jpeg" -> startsWith(prefix, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                case ".png" -> startsWith(prefix, new byte[] {
                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
                case ".webp" -> prefix.length >= 12
                        && startsWith(prefix, new byte[] {'R', 'I', 'F', 'F'})
                        && prefix[8] == 'W' && prefix[9] == 'E' && prefix[10] == 'B' && prefix[11] == 'P';
                case ".mp3" -> startsWith(prefix, new byte[] {'I', 'D', '3'})
                        || (prefix.length >= 2 && (prefix[0] & 0xff) == 0xff
                        && (prefix[1] & 0xe0) == 0xe0);
                case ".wav" -> prefix.length >= 12
                        && startsWith(prefix, new byte[] {'R', 'I', 'F', 'F'})
                        && prefix[8] == 'W' && prefix[9] == 'A' && prefix[10] == 'V' && prefix[11] == 'E';
                case ".m4a" -> prefix.length >= 12
                        && prefix[4] == 'f' && prefix[5] == 't' && prefix[6] == 'y' && prefix[7] == 'p';
                default -> false;
            };
        }

        private static MediaDescriptor requireType(
                String actual, String expected, MediaDescriptor descriptor) {
            if (!Objects.equals(actual, expected)) {
                throw new IllegalArgumentException("Declared MIME type does not match filename");
            }
            return descriptor;
        }

        private static MediaDescriptor requireTypeAny(
                String actual, String[] expected, MediaDescriptor descriptor) {
            for (String value : expected) {
                if (Objects.equals(actual, value)) return descriptor;
            }
            throw new IllegalArgumentException("Declared MIME type does not match filename");
        }

        private static String extension(String filename) {
            int dot = filename.lastIndexOf('.');
            if (dot < 0 || dot == filename.length() - 1) {
                throw new IllegalArgumentException("Attachment extension is required");
            }
            return filename.substring(dot).toLowerCase(Locale.ROOT);
        }

        private static boolean startsWith(byte[] bytes, byte[] expected) {
            if (bytes.length < expected.length) return false;
            for (int index = 0; index < expected.length; index++) {
                if (bytes[index] != expected[index]) return false;
            }
            return true;
        }

        private static boolean containsNul(byte[] bytes) {
            for (byte value : bytes) if (value == 0) return true;
            return false;
        }
    }
}
