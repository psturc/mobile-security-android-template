package com.feedhenry.securenativeandroidtemplate.domain.crypto;

import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.inject.Inject;

/**
 * Perform data encryption and decryption using AES/GCM/NoPadding alg. It requires at least API level 19.
 * To support lower versions (the total of which are less than 10% of the android market share), take a look at using https://github.com/tozny/java-aes-crypto/blob/master/aes-crypto/src/main/java/com/tozny/crypto/android/AesCbcWithIntegrity.java.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
public class AesCrypto {

    private static final int BASE64_FLAG = Base64.NO_WRAP;

    private SecureKeyStore secureKeyStore;

    @Inject
    public AesCrypto(SecureKeyStore secureKeyStore) {
        this.secureKeyStore = secureKeyStore;
    }

    /**
     * Load the secret key from the keystore using the given key alias if it already exists, or generate a new one if it doesn't exist.
     * @param keyAlias the alias of the key
     * @return the SecretKey instance.
     * @throws GeneralSecurityException
     * @throws IOException
     */
    private SecretKey loadOrGenerateSecretKey(String keyAlias, boolean doGenerate) throws GeneralSecurityException, IOException {
        if (!this.secureKeyStore.hasSecretKey(keyAlias)) {
            if (doGenerate) {
                this.secureKeyStore.generateAESKey(keyAlias);
            } else {
                throw new GeneralSecurityException("missing alias " + keyAlias);
            }
        }
        SecretKey secretKey = (SecretKey) this.secureKeyStore.getSecretKey(keyAlias);
        return secretKey;

    }

    // tag::encrypt[]
    /**
     * Encrypt the given text.
     * @param keyAlias The alias of the key in the keystore that will be used for the encryption.
     * @param plainText the text to encrypt
     * @return the encrypted data. The first 12 bytes will be the IV (initial vector) used for the encryption.
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public byte[] encrypt(String keyAlias, byte[] plainText) throws GeneralSecurityException, IOException {
        SecretKey secretKey = loadOrGenerateSecretKey(keyAlias, true);
        Cipher cipher = Cipher.getInstance(secureKeyStore.getSupportedAESMode());
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        //get the iv that is being used
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plainText);
        GCMEncrypted encryptedData = new GCMEncrypted(iv, encrypted);
        return encryptedData.toByteArray();
    }
    // end::encrypt[]

    // tag::decrypt[]
    /**
     * Decrypt the given encrypted data
     * @param keyAlias The alias of the key in the keystore that will be used for the decryption.
     * @param encryptedText the text to decrypt. The first 12 bytes should be the IV used for encryption.
     * @return the plain text data
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public byte[] decrypt(String keyAlias, byte[] encryptedText) throws GeneralSecurityException, IOException {
        GCMEncrypted encryptedData = GCMEncrypted.parse(encryptedText);
        SecretKey secretKey = loadOrGenerateSecretKey(keyAlias, false);
        Cipher cipher = Cipher.getInstance(secureKeyStore.getSupportedAESMode());
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCMEncrypted.GCM_TAG_LENGTH, encryptedData.iv));
        byte[] plainText = cipher.doFinal(encryptedData.encryptedData);
        return plainText;
    }
    // end::decrypt[]

    /**
     * Encrypt the given string. The encrypted data will be returned as a base64-encoded string.
     * @param keyAlias The alias of the key in the keystore that will be used for the encryption.
     * @param plainText the string to encrypt
     * @param encoding the encode of the plain text
     * @return encrypted data in base64-encoded string
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public String encryptString(String keyAlias, String plainText, String encoding) throws GeneralSecurityException, IOException {
        return Base64.encodeToString(encrypt(keyAlias, plainText.getBytes(encoding)), BASE64_FLAG);
    }

    /**
     * Returns an OutputStream that will automatically encrypt data while writing. The IV will be first written to the original stream without encryption.
     * NOTE: don't write to the original stream directly in the calling method.
     * @param keyAlias the alias of the secret key to use
     * @param outputStream the original output stream.
     * @return The output stream that will encrypt the data
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public OutputStream encryptStream(String keyAlias, OutputStream outputStream) throws GeneralSecurityException, IOException {
        SecretKey secretKey = loadOrGenerateSecretKey(keyAlias, true);
        Cipher cipher = Cipher.getInstance(secureKeyStore.getSupportedAESMode());
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        //get the iv that is being used
        byte[] iv = cipher.getIV();
        //need to write down the size of the iv as different provider will generate iv with different size
        byte[] ivLengthBytes = ByteBuffer.allocate(4).putInt(iv.length).array();
        outputStream.write(ivLengthBytes);
        outputStream.write(iv);
        CipherOutputStream cipherStream = new CipherOutputStream(outputStream, cipher);
        return cipherStream;
    }

    /**
     * Decrypted the encrypted input stream, and return the plain text input stream.
     * @param keyAlias the alias of the secret key to use
     * @param inputStream the encrypted input stream. The first 12 bytes should be the IV.
     * @return the plain text input stream
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public InputStream decryptStream(String keyAlias, InputStream inputStream) throws GeneralSecurityException, IOException {
        SecretKey secretKey = loadOrGenerateSecretKey(keyAlias, false);
        byte[] ivLengthBytes = new byte[4];
        inputStream.read(ivLengthBytes);
        int ivLength = ByteBuffer.wrap(ivLengthBytes).getInt();
        byte[] iv = new byte[ivLength];
        inputStream.read(iv);
        Cipher cipher = Cipher.getInstance(secureKeyStore.getSupportedAESMode());
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCMEncrypted.GCM_TAG_LENGTH, iv));
        CipherInputStream cipherStream = new CipherInputStream(inputStream, cipher);
        return cipherStream;
    }

    /**
     * Decrypt the given string. The encrypted data should be base64-encoded, and no line separators (it's in one line).
     * @param keyAlias The alias of the key in the keystore that will be used for the decryption.
     * @param encryptedText The text to decrypt. It should be base64-encoded, and has no line separators.
     * @param encoding the encoding of the decrypted data.
     * @return the decrypted string data
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public String decryptString(String keyAlias, String encryptedText, String encoding) throws GeneralSecurityException, IOException {
        return new String(decrypt(keyAlias, Base64.decode(encryptedText, BASE64_FLAG)), encoding);
    }

    /**
     * Remove the key entry from the keystore that has the given keyAlias
     * @param keyAlias the alias of the key
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public void deleteSecretKey(String keyAlias) throws GeneralSecurityException, IOException {
        this.secureKeyStore.deleteKey(keyAlias);
    }

    static class GCMEncrypted {
        private static final int GCM_TAG_LENGTH = 128;
        private byte[] iv;
        //apparently different providers could generate IVs with different length
        private int ivLength;
        private byte[] encryptedData;

        private GCMEncrypted() {

        }

        GCMEncrypted(byte[] iv, byte[] data) {
            this.iv = iv;
            this.ivLength = iv.length;
            this.encryptedData = data;
        }

        public byte[] toByteArray() {
            ByteBuffer bb = ByteBuffer.allocate(4 + this.iv.length + this.encryptedData.length);
            bb.put(ByteBuffer.allocate(4).putInt(this.ivLength).array());
            bb.put(this.iv);
            bb.put(this.encryptedData);
            return bb.array();
        }

        public static GCMEncrypted parse(byte[] encrypted) {
            int ivLength = ByteBuffer.wrap(Arrays.copyOfRange(encrypted, 0, 4)).getInt();
            ByteBuffer bb = ByteBuffer.wrap(encrypted);
            GCMEncrypted gcmData = new GCMEncrypted();
            byte[] ivLengthBytes = new byte[4];
            byte[] ivArr = new byte[ivLength];
            byte[] dataArr = new byte[encrypted.length - ivLength - 4];
            bb.get(ivLengthBytes);
            bb.get(ivArr);
            bb.get(dataArr);
            gcmData.iv = ivArr;
            gcmData.encryptedData = dataArr;
            gcmData.ivLength = ivLength;
            return gcmData;
        }
    }
}
