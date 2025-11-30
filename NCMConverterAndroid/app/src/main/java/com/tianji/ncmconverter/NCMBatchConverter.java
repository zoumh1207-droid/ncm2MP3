package com.tianji.ncmconverter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class NCMBatchConverter {

    // NCM文件的核心密钥
    private static final byte[] CORE_KEY = {
            0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
            0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
    };

    // Meta密钥
    private static final byte[] META_KEY = {
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    };

    public static boolean DEBUG = false;

    public interface Logger {
        void info(String s);
        void error(String s);
    }

    public static void runConversion(String folderPath, String outputFolder, Logger logger) throws Exception {
        logger.info("扫描: " + folderPath);
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            logger.error("输入文件夹不存在: " + folderPath);
            return;
        }
        File outDir = new File(outputFolder);
        if (!outDir.exists()) outDir.mkdirs();

        try (Stream<Path> paths = Files.walk(Paths.get(folderPath))) {
            Path[] ncmFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".ncm"))
                    .toArray(Path[]::new);

            if (ncmFiles.length == 0) {
                logger.info("未找到 NCM 文件");
                return;
            }

            logger.info("找到 " + ncmFiles.length + " 个 NCM 文件");
            int success = 0, failed = 0;
            for (int i = 0; i < ncmFiles.length; i++) {
                Path ncmFile = ncmFiles[i];
                String inputPath = ncmFile.toString();
                String fileName = ncmFile.getFileName().toString().replaceAll("(?i)\\.ncm$", ".mp3");
                String outputPath = Paths.get(outputFolder, fileName).toString();
                logger.info(String.format("[%d/%d] 转换: %s", i+1, ncmFiles.length, ncmFile.getFileName()));
                try {
                    convertNCMToMP3(inputPath, outputPath);
                    logger.info("  ✓ 成功: " + fileName);
                    success++;
                } catch (Exception e) {
                    logger.error("  ✗ 失败: " + e.getMessage());
                    if (DEBUG) e.printStackTrace();
                    failed++;
                }
            }
            logger.info(String.format("转换结束 — 成功: %d, 失败: %d", success, failed));
        }
    }

    public static String convertNCMToMP3(String inputPath, String outputPath) throws Exception {
        byte[] fileData = Files.readAllBytes(Paths.get(inputPath));

        // 验证头部
        if (fileData.length < 10 ||
                fileData[0] != 0x43 || fileData[1] != 0x54 ||
                fileData[2] != 0x45 || fileData[3] != 0x4E ||
                fileData[4] != 0x46 || fileData[5] != 0x44 ||
                fileData[6] != 0x41 || fileData[7] != 0x4D) {
            throw new IllegalArgumentException("不是有效的NCM文件: " + inputPath);
        }

        int pos = 10;
        int keyLen = readInt(fileData, pos);
        pos += 4;
        byte[] keyData = new byte[keyLen];
        for (int i = 0; i < keyLen; i++) keyData[i] = (byte)(fileData[pos + i] ^ 0x64);
        pos += keyLen;
        byte[] decryptedKey = aesDecrypt(keyData, CORE_KEY);
        byte[] rc4Key = Arrays.copyOfRange(decryptedKey, 17, decryptedKey.length);

        int metaLen = readInt(fileData, pos);
        pos += 4;
        byte[] coverImage = null;
        if (metaLen > 0 && metaLen < fileData.length - pos) {
            byte[] metaData = new byte[metaLen];
            for (int i = 0; i < metaLen; i++) metaData[i] = (byte)(fileData[pos + i] ^ 0x63);
            // 解析 meta 略（保持简单）
            pos += metaLen;
        }
        pos += 4; // skip crc
        pos += 5; // gap
        int imageLen = readInt(fileData, pos);
        pos += 4;
        if (imageLen > 0 && imageLen < fileData.length - pos) {
            coverImage = Arrays.copyOfRange(fileData, pos, pos + imageLen);
            pos += imageLen;
        }

        byte[] audioData = decryptAudio(fileData, pos, rc4Key);
        String finalOutputPath = detectAndSetFormat(audioData, outputPath);
        Files.write(Paths.get(finalOutputPath), audioData);
        return finalOutputPath;
    }

    private static String detectAndSetFormat(byte[] audioData, String outputPath) {
        if (audioData.length < 4) return outputPath;
        if ((audioData[0] == 'I' && audioData[1] == 'D' && audioData[2] == '3') ||
                (audioData[0] == (byte)0xFF && (audioData[1] & 0xE0) == 0xE0)) {
            return outputPath.replaceAll("(?i)\\.(mp3|flac|m4a)$", ".mp3");
        } else if (audioData[0] == 'f' && audioData[1] == 'L' && audioData[2] == 'a' && audioData[3] == 'C') {
            return outputPath.replaceAll("(?i)\\.(mp3|flac|m4a)$", ".flac");
        } else if (audioData.length >= 8 && audioData[4] == 'f' && audioData[5] == 't' && audioData[6] == 'y' && audioData[7] == 'p') {
            return outputPath.replaceAll("(?i)\\.(mp3|flac|m4a)$", ".m4a");
        }
        return outputPath;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF)) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    private static byte[] aesDecrypt(byte[] data, byte[] key) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        return cipher.doFinal(data);
    }

    private static byte[] decryptAudio(byte[] data, int offset, byte[] key) {
        int[] keyBox = new int[256];
        for (int i = 0; i < 256; i++) keyBox[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + keyBox[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int temp = keyBox[i];
            keyBox[i] = keyBox[j];
            keyBox[j] = temp;
        }
        int audioLen = data.length - offset;
        byte[] result = new byte[audioLen];
        for (int i = 1; i <= audioLen; i++) {
            j = i & 0xFF;
            int k = (keyBox[j] + keyBox[(keyBox[j] + j) & 0xFF]) & 0xFF;
            result[i - 1] = (byte)(data[offset + i - 1] ^ keyBox[k]);
        }
        return result;
    }
}
