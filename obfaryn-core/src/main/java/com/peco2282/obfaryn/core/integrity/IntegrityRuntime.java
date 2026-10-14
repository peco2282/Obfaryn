package com.peco2282.obfaryn.core.integrity;

import com.peco2282.obfaryn.core.context.ObfuscationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Provides runtime integrity verification of JAR files based on a manifest and
 * signature mechanism. The integrity verification ensures the authenticity and
 * consistency of the application JAR contents, including its associated integrity
 * metadata.
 * This class is final and cannot be instantiated.
 *
 * @see IntegrityProtection#inject(ObfuscationContext, Set, Random)
 */
@SuppressWarnings("unused")
public final class IntegrityRuntime {
  private static final String MANIFEST = "META-INF/obfaryn/integrity.bin";
  private static final String SIGNATURE = "META-INF/obfaryn/integrity.sig";
  private static final Set<String> VERIFIED = new HashSet<>();

  private IntegrityRuntime() {}

  public static synchronized void verify(Class<?> anchor, String publicKeyBase64) {
    try {
      URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
      if (location == null || !"file".equals(location.getProtocol())) {
        throw new SecurityException("Integrity anchor must originate from a local JAR");
      }
      File file = new File(location.toURI());
      if (!file.isFile()) {
        throw new SecurityException("Integrity anchor must originate from a JAR file");
      }
      String identity = new StringBuilder(file.getCanonicalPath()).append('\n').append(file.length())
          .append('\n').append(file.lastModified()).append('\n').append(publicKeyBase64).toString();
      if (VERIFIED.contains(identity)) return;
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
          new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
      try (JarFile jar = new JarFile(file, false)) {
        Set<String> names = new HashSet<>();
        Map<String, JarEntry> entries = new HashMap<>();
        JarEntry manifestEntry = null;
        JarEntry signatureEntry = null;
        Enumeration<JarEntry> enumeration = jar.entries();
        while (enumeration.hasMoreElements()) {
          JarEntry entry = enumeration.nextElement();
          String name = entry.getName();
          if (!names.add(name)) {
            throw new SecurityException(new StringBuilder("Duplicate JAR entry: ").append(name).toString());
          }
          if (MANIFEST.equals(name)) {
            manifestEntry = entry;
          } else if (SIGNATURE.equals(name)) {
            signatureEntry = entry;
          } else if (!entry.isDirectory()) {
            entries.put(name, entry);
          }
        }
        if (manifestEntry == null || signatureEntry == null
            || manifestEntry.isDirectory() || signatureEntry.isDirectory()) {
          throw new SecurityException("Missing integrity metadata");
        }
        byte[] manifest = readBytes(jar, manifestEntry);
        byte[] signature = readBytes(jar, signatureEntry);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(manifest);
        if (!verifier.verify(signature)) {
          throw new SecurityException("Invalid integrity signature");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(manifest))) {
          if (input.readInt() != 0x56424931) {
            throw new SecurityException("Invalid integrity manifest magic");
          }
          int count = input.readInt();
          if (count < 0 || count != entries.size()) {
            throw new SecurityException("Integrity entry count mismatch");
          }
          Set<String> manifestNames = new HashSet<>();
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          byte[] buffer = new byte[8192];
          for (int i = 0; i < count; i++) {
            String name = input.readUTF();
            if (!manifestNames.add(name) || MANIFEST.equals(name) || SIGNATURE.equals(name)) {
              throw new SecurityException(new StringBuilder("Invalid or duplicate manifest entry: ").append(name).toString());
            }
            if (input.readInt() != 32) {
              throw new SecurityException("Invalid integrity digest length");
            }
            byte[] expected = new byte[32];
            input.readFully(expected);
            JarEntry entry = entries.remove(name);
            if (entry == null) {
              throw new SecurityException(new StringBuilder("Missing JAR entry: ").append(name).toString());
            }
            digest.reset();
            try (InputStream content = jar.getInputStream(entry)) {
              int length;
              while ((length = content.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
              }
            }
            if (!MessageDigest.isEqual(expected, digest.digest())) {
              throw new SecurityException(new StringBuilder("Integrity digest mismatch: ").append(name).toString());
            }
          }
          if (input.read() != -1 || !entries.isEmpty()) {
            throw new SecurityException("Unexpected integrity manifest data or JAR entries");
          }
        }
      }
      VERIFIED.add(identity);
    } catch (SecurityException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SecurityException("JAR integrity verification failed", exception);
    }
  }

  private static byte[] readBytes(JarFile jar, JarEntry entry) throws IOException {
    try (InputStream input = jar.getInputStream(entry);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int length;
      while ((length = input.read(buffer)) != -1) {
        output.write(buffer, 0, length);
      }
      return output.toByteArray();
    }
  }
}