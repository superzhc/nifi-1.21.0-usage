/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.security.util.crypto

import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.binary.Hex
import org.apache.nifi.security.util.EncryptionMethod
import org.apache.nifi.security.util.crypto.scrypt.Scrypt
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.security.Security

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class ScryptCipherProviderGroovyTest {
    private static final Logger logger = LoggerFactory.getLogger(ScryptCipherProviderGroovyTest.class)

    private static final String PLAINTEXT = "ExactBlockSizeRequiredForProcess"

    private static List<EncryptionMethod> strongKDFEncryptionMethods

    private static final int DEFAULT_KEY_LENGTH = 128
    public static final String MICROBENCHMARK = "microbenchmark"
    private static ArrayList<Integer> AES_KEY_LENGTHS

    RandomIVPBECipherProvider cipherProvider

    @BeforeAll
    static void setUpOnce() throws Exception {
        Security.addProvider(new BouncyCastleProvider())

        strongKDFEncryptionMethods = EncryptionMethod.values().findAll { it.isCompatibleWithStrongKDFs() }

        logger.metaClass.methodMissing = { String name, args ->
            logger.info("[${name?.toUpperCase()}] ${(args as List).join(" ")}")
        }

        AES_KEY_LENGTHS = [128, 192, 256]
    }

    @BeforeEach
    void setUp() throws Exception {
        // Very fast parameters to test for correctness rather than production values
        cipherProvider = new ScryptCipherProvider(4, 1, 1)
    }

    @Test
    void testGetCipherShouldBeInternallyConsistent() throws Exception {
        // Arrange
        final String PASSWORD = "shortPassword"
        final byte[] SALT = cipherProvider.generateSalt()

        // Act
        for (EncryptionMethod em : strongKDFEncryptionMethods) {
            logger.info("Using algorithm: ${em.getAlgorithm()}")

            // Initialize a cipher for encryption
            Cipher cipher = cipherProvider.getCipher(em, PASSWORD, SALT, DEFAULT_KEY_LENGTH, true)
            byte[] iv = cipher.getIV()
            logger.info("IV: ${Hex.encodeHexString(iv)}")

            byte[] cipherBytes = cipher.doFinal(PLAINTEXT.getBytes("UTF-8"))
            logger.info("Cipher text: ${Hex.encodeHexString(cipherBytes)} ${cipherBytes.length}")

            cipher = cipherProvider.getCipher(em, PASSWORD, SALT, iv, DEFAULT_KEY_LENGTH, false)
            byte[] recoveredBytes = cipher.doFinal(cipherBytes)
            String recovered = new String(recoveredBytes, "UTF-8")
            logger.info("Recovered: ${recovered}")

            // Assert
            assertEquals(PLAINTEXT, recovered)
        }
    }

    @Test
    void testGetCipherWithExternalIVShouldBeInternallyConsistent() throws Exception {
        // Arrange
        final String PASSWORD = "shortPassword"
        final byte[] SALT = cipherProvider.generateSalt()
        final byte[] IV = Hex.decodeHex("01" * 16 as char[])

        // Act
        for (EncryptionMethod em : strongKDFEncryptionMethods) {
            logger.info("Using algorithm: ${em.getAlgorithm()}")

            // Initialize a cipher for encryption
            Cipher cipher = cipherProvider.getCipher(em, PASSWORD, SALT, IV, DEFAULT_KEY_LENGTH, true)
            logger.info("IV: ${Hex.encodeHexString(IV)}")

            byte[] cipherBytes = cipher.doFinal(PLAINTEXT.getBytes("UTF-8"))
            logger.info("Cipher text: ${Hex.encodeHexString(cipherBytes)} ${cipherBytes.length}")

            cipher = cipherProvider.getCipher(em, PASSWORD, SALT, IV, DEFAULT_KEY_LENGTH, false)
            byte[] recoveredBytes = cipher.doFinal(cipherBytes)
            String recovered = new String(recoveredBytes, "UTF-8")
            logger.info("Recovered: ${recovered}")

            // Assert
            assertEquals(PLAINTEXT, recovered)
        }
    }

    @Test
    void testGetCipherWithUnlimitedStrengthShouldBeInternallyConsistent() throws Exception {
        // Arrange
        final String PASSWORD = "shortPassword"
        final byte[] SALT = cipherProvider.generateSalt()

        final int LONG_KEY_LENGTH = 256

        // Act
        for (EncryptionMethod em : strongKDFEncryptionMethods) {
            logger.info("Using algorithm: ${em.getAlgorithm()}")

            // Initialize a cipher for encryption
            Cipher cipher = cipherProvider.getCipher(em, PASSWORD, SALT, LONG_KEY_LENGTH, true)
            byte[] iv = cipher.getIV()
            logger.info("IV: ${Hex.encodeHexString(iv)}")

            byte[] cipherBytes = cipher.doFinal(PLAINTEXT.getBytes("UTF-8"))
            logger.info("Cipher text: ${Hex.encodeHexString(cipherBytes)} ${cipherBytes.length}")

            cipher = cipherProvider.getCipher(em, PASSWORD, SALT, iv, LONG_KEY_LENGTH, false)
            byte[] recoveredBytes = cipher.doFinal(cipherBytes)
            String recovered = new String(recoveredBytes, "UTF-8")
            logger.info("Recovered: ${recovered}")

            // Assert
            assertEquals(PLAINTEXT, recovered)
        }
    }

    @Test
    void testScryptShouldSupportExternalCompatibility() throws Exception {
        // Arrange

        // Default values are N=2^14, r=8, p=1, but the provided salt will contain the parameters used
        cipherProvider = new ScryptCipherProvider()

        final String PLAINTEXT = "This is a plaintext message."
        final String PASSWORD = "thisIsABadPassword"
        final int DK_LEN = 128

        // These values can be generated by running `$ ./openssl_scrypt.rb` in the terminal
        final byte[] SALT = Hex.decodeHex("f5b8056ea6e66edb8d013ac432aba24a" as char[])
        logger.info("Expected salt: ${Hex.encodeHexString(SALT)}")
        final byte[] IV = Hex.decodeHex("76a00f00878b8c3db314ae67804c00a1" as char[])

        final String CIPHER_TEXT = "604188bf8e9137bc1b24a0ab01973024bc5935e9ae5fedf617bdca028c63c261"
        logger.sanity("Ruby cipher text: ${CIPHER_TEXT}")
        byte[] cipherBytes = Hex.decodeHex(CIPHER_TEXT as char[])

        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC

        // Sanity check
        String rubyKeyHex = "a8efbc0a709d3f89b6bb35b05fc8edf5"
        logger.sanity("Using key: ${rubyKeyHex}")
        logger.sanity("Using IV:  ${Hex.encodeHexString(IV)}")
        Cipher rubyCipher = Cipher.getInstance(encryptionMethod.algorithm, "BC")
        def rubyKey = new SecretKeySpec(Hex.decodeHex(rubyKeyHex as char[]), "AES")
        def ivSpec = new IvParameterSpec(IV)
        rubyCipher.init(Cipher.ENCRYPT_MODE, rubyKey, ivSpec)
        byte[] rubyCipherBytes = rubyCipher.doFinal(PLAINTEXT.bytes)
        logger.sanity("Created cipher text: ${Hex.encodeHexString(rubyCipherBytes)}")
        rubyCipher.init(Cipher.DECRYPT_MODE, rubyKey, ivSpec)
        assertArrayEquals(PLAINTEXT.bytes, rubyCipher.doFinal(rubyCipherBytes))
        logger.sanity("Decrypted generated cipher text successfully")
        assertArrayEquals(PLAINTEXT.bytes, rubyCipher.doFinal(cipherBytes))
        logger.sanity("Decrypted external cipher text successfully")

        // n$r$p$hex_salt_SL$hex_hash_HL
        final String FULL_HASH = "400\$8\$24\$f5b8056ea6e66edb8d013ac432aba24a\$a8efbc0a709d3f89b6bb35b05fc8edf5"
        logger.info("Full Hash: ${FULL_HASH}")

        def (String nStr, String rStr, String pStr, String saltHex, String hashHex) = FULL_HASH.split("\\\$")
        def (n, r, p) = [nStr, rStr, pStr].collect { Integer.valueOf(it, 16) }

        logger.info("N: Hex ${nStr} -> ${n}")
        logger.info("r: Hex ${rStr} -> ${r}")
        logger.info("p: Hex ${pStr} -> ${p}")
        logger.info("Salt: ${saltHex}")
        logger.info("Hash: ${hashHex}")

        // Form Java-style salt with cost params from Ruby-style
        String javaSalt = Scrypt.formatSalt(Hex.decodeHex(saltHex as char[]), n, r, p)
        logger.info("Formed Java-style salt: ${javaSalt}")

        // Convert hash from hex to Base64
        String base64Hash = CipherUtility.encodeBase64NoPadding(Hex.decodeHex(hashHex as char[]))
        logger.info("Converted hash from hex ${hashHex} to Base64 ${base64Hash}")
        assertEquals(hashHex, Hex.encodeHexString(Base64.decodeBase64(base64Hash)))

        logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()}")
        logger.info("External cipher text: ${CIPHER_TEXT} ${cipherBytes.length}")

        // Act
        Cipher cipher = cipherProvider.getCipher(encryptionMethod, PASSWORD, javaSalt.bytes, IV, DK_LEN, false)
        byte[] recoveredBytes = cipher.doFinal(cipherBytes)
        String recovered = new String(recoveredBytes, "UTF-8")
        logger.info("Recovered: ${recovered}")

        // Assert
        assertEquals(PLAINTEXT, recovered)
    }

    @Test
    void testGetCipherShouldHandleSaltWithoutParameters() throws Exception {
        // Arrange

        // To help Groovy resolve implementation private methods not known at interface level
        cipherProvider = cipherProvider as ScryptCipherProvider

        final String PASSWORD = "shortPassword"
        final byte[] SALT = new byte[cipherProvider.defaultSaltLength]
        new SecureRandom().nextBytes(SALT)
//        final byte[] SALT = [0x00] * 16 as byte[]

        final String EXPECTED_FORMATTED_SALT = cipherProvider.formatSaltForScrypt(SALT)
        logger.info("Expected salt: ${EXPECTED_FORMATTED_SALT}")

        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC
        logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()}")

        // Act

        // Initialize a cipher for encryption
        Cipher cipher = cipherProvider.getCipher(encryptionMethod, PASSWORD, SALT, DEFAULT_KEY_LENGTH, true)
        byte[] iv = cipher.getIV()
        logger.info("IV: ${Hex.encodeHexString(iv)}")

        byte[] cipherBytes = cipher.doFinal(PLAINTEXT.getBytes("UTF-8"))
        logger.info("Cipher text: ${Hex.encodeHexString(cipherBytes)} ${cipherBytes.length}")

        // Manually initialize a cipher for decrypt with the expected salt
        byte[] parsedSalt = new byte[cipherProvider.defaultSaltLength]
        def params = []
        cipherProvider.parseSalt(EXPECTED_FORMATTED_SALT, parsedSalt, params)
        def (int n, int r, int p) = params
        byte[] keyBytes = Scrypt.deriveScryptKey(PASSWORD.bytes, parsedSalt, n, r, p, DEFAULT_KEY_LENGTH)
        logger.info("Manually derived key bytes: ${Hex.encodeHexString(keyBytes)}")
        SecretKey key = new SecretKeySpec(keyBytes, "AES")
        Cipher manualCipher = Cipher.getInstance(encryptionMethod.algorithm, encryptionMethod.provider)
        manualCipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv))
        byte[] recoveredBytes = manualCipher.doFinal(cipherBytes)
        String recovered = new String(recoveredBytes, "UTF-8")
        logger.info("Recovered: ${recovered}")

        // Assert
        assertEquals(PLAINTEXT, recovered)
    }

    @Test
    void testGetCipherShouldNotAcceptInvalidSalts() throws Exception {
        // Arrange
        final String PASSWORD = "thisIsABadPassword"

        final def INVALID_SALTS = ['bad_sal', '$3a$11$', 'x', '$2a$10$']
        final LENGTH_MESSAGE = "The raw salt must be greater than or equal to 8 bytes"

        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC
        logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()}")

        // Act
        INVALID_SALTS.each { String salt ->
            logger.info("Checking salt ${salt}")

            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                    () -> cipherProvider.getCipher(encryptionMethod, PASSWORD, salt.bytes, DEFAULT_KEY_LENGTH, true))
            logger.warn(iae.getMessage())

            // Assert
            assertTrue(iae.getMessage().contains(LENGTH_MESSAGE))
        }
    }

    @Test
    void testGetCipherShouldHandleUnformattedSalts() throws Exception {
        // Arrange
        final String PASSWORD = "thisIsABadPassword"

        final def RECOVERABLE_SALTS = ['$ab$00$acbdefghijklmnopqrstuv', '$4$1$1$0123456789abcdef', '$400$1$1$abcdefghijklmnopqrstuv']

        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC
        logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()}")

        // Act
        RECOVERABLE_SALTS.each { String salt ->
            logger.info("Checking salt ${salt}")

            Cipher cipher = cipherProvider.getCipher(encryptionMethod, PASSWORD, salt.bytes, DEFAULT_KEY_LENGTH, true)

            // Assert
            assertNotNull(cipher)
        }
    }

    @Test
    void testGetCipherShouldRejectEmptySalt() throws Exception {
        // Arrange
        final String PASSWORD = "thisIsABadPassword"

        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC
        logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()}")

        // Act
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> cipherProvider.getCipher(encryptionMethod, PASSWORD, new byte[0], DEFAULT_KEY_LENGTH, true))
        logger.warn(iae.getMessage())

        // Assert
        assertTrue(iae.getMessage().contains("The salt cannot be empty. To generate a salt, use ScryptCipherProvider#generateSalt"))
    }

    @Test
    void testGetCipherForDecryptShouldRequireIV() throws Exception {
        // Arrange
        final String PASSWORD = "shortPassword"
        final byte[] SALT = cipherProvider.generateSalt()
        final byte[] IV = Hex.decodeHex("00" * 16 as char[])

        // Act
        for (EncryptionMethod em : strongKDFEncryptionMethods) {
            logger.info("Using algorithm: ${em.getAlgorithm()}")

            // Initialize a cipher for encryption
            Cipher cipher = cipherProvider.getCipher(em, PASSWORD, SALT, IV, DEFAULT_KEY_LENGTH, true)
            logger.info("IV: ${Hex.encodeHexString(IV)}")

            byte[] cipherBytes = cipher.doFinal(PLAINTEXT.getBytes("UTF-8"))
            logger.info("Cipher text: ${Hex.encodeHexString(cipherBytes)} ${cipherBytes.length}")

            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                    () -> cipherProvider.getCipher(em, PASSWORD, SALT, DEFAULT_KEY_LENGTH, false))
            logger.warn(iae.getMessage())

            // Assert
            assertTrue(iae.getMessage().contains("Cannot decrypt without a valid IV"))
        }
    }

    @Test
    void testGetCipherShouldAcceptValidKeyLengths() throws Exception {
        // Arrange
        final String PASSWORD = "shortPassword"
        final byte[] SALT = cipherProvider.generateSalt()
        final byte[] IV = Hex.decodeHex("01" * 16 as char[])

        final def VALID_KEY_LENGTHS = AES_KEY_LENGTHS
        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC

        // Act
        VALID_KEY_LENGTHS.each { int keyLength ->
            logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()} with key length ${keyLength}")

            // Initialize a cipher for encryption
            Cipher cipher = cipherProvider.getCipher(encryptionMethod, PASSWORD, SALT, IV, keyLength, true)
            logger.info("IV: ${Hex.encodeHexString(IV)}")

            byte[] cipherBytes = cipher.doFinal(PLAINTEXT.getBytes("UTF-8"))
            logger.info("Cipher text: ${Hex.encodeHexString(cipherBytes)} ${cipherBytes.length}")

            cipher = cipherProvider.getCipher(encryptionMethod, PASSWORD, SALT, IV, keyLength, false)
            byte[] recoveredBytes = cipher.doFinal(cipherBytes)
            String recovered = new String(recoveredBytes, "UTF-8")
            logger.info("Recovered: ${recovered}")

            // Assert
            assertEquals(PLAINTEXT, recovered)
        }
    }

    @Test
    void testGetCipherShouldNotAcceptInvalidKeyLengths() throws Exception {
        // Arrange
        final String PASSWORD = "shortPassword"
        final byte[] SALT = cipherProvider.generateSalt()
        final byte[] IV = Hex.decodeHex("00" * 16 as char[])

        // Even though Scrypt can derive keys of arbitrary length, it will fail to validate if the underlying cipher does not support it
        final def INVALID_KEY_LENGTHS = [-1, 40, 64, 112, 512]
        // Currently only AES ciphers are compatible with Scrypt, so redundant to test all algorithms
        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC

        // Act
        INVALID_KEY_LENGTHS.each { int keyLength ->
            logger.info("Using algorithm: ${encryptionMethod.getAlgorithm()} with key length ${keyLength}")

            // Initialize a cipher for encryption
            IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                    () -> cipherProvider.getCipher(encryptionMethod, PASSWORD, SALT, IV, keyLength, true))
            logger.warn(iae.getMessage())

            // Assert
            assertTrue(iae.getMessage().contains(keyLength + " is not a valid key length for AES"))
        }
    }

    @Test
    void testScryptShouldNotAcceptInvalidPassword() {
        // Arrange
        String badPassword = ""
        byte[] salt = [0x01 as byte] * 16

        EncryptionMethod encryptionMethod = EncryptionMethod.AES_CBC

        // Act
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                ()-> cipherProvider.getCipher(encryptionMethod, badPassword, salt, DEFAULT_KEY_LENGTH, true))

        // Assert
        assertTrue(iae.getMessage().contains("Encryption with an empty password is not supported"))
    }

    @Test
    void testGenerateSaltShouldUseProvidedParameters() throws Exception {
        // Arrange
        RandomIVPBECipherProvider cipherProvider = new ScryptCipherProvider(8, 2, 2)
        int n = cipherProvider.getN()
        int r = cipherProvider.getR()
        int p = cipherProvider.getP()

        // Act
        final String salt = new String(cipherProvider.generateSalt())
        logger.info("Salt: ${salt}")

        // Assert
        assertTrue((salt =~ "^(?i)\\\$s0\\\$[a-f0-9]{5,16}\\\$").find())
        String params = Scrypt.encodeParams(n, r, p)
        assertTrue(salt.contains("\$${params}\$"))
    }

    @Test
    void testShouldParseSalt() throws Exception {
        // Arrange
        cipherProvider = cipherProvider as ScryptCipherProvider

        final byte[] EXPECTED_RAW_SALT = Hex.decodeHex("f5b8056ea6e66edb8d013ac432aba24a" as char[])
        final int EXPECTED_N = 1024
        final int EXPECTED_R = 8
        final int EXPECTED_P = 36

        final String FORMATTED_SALT = "\$s0\$a0824\$9bgFbqbmbtuNATrEMquiSg"
        logger.info("Using salt: ${FORMATTED_SALT}")

        byte[] rawSalt = new byte[16]
        def params = []

        // Act
        cipherProvider.parseSalt(FORMATTED_SALT, rawSalt, params)

        // Assert
        assertArrayEquals(EXPECTED_RAW_SALT, rawSalt)
        assertEquals(EXPECTED_N, params[0])
        assertEquals(EXPECTED_R, params[1])
        assertEquals(EXPECTED_P, params[2])
    }

    @Test
    void testShouldVerifyPBoundary() throws Exception {
        // Arrange
        final int r = 8
        final int p = 1

        // Act
        boolean valid = ScryptCipherProvider.isPValid(r, p)

        // Assert
        assertTrue(valid)
    }

    @Test
    void testShouldFailPBoundary() throws Exception {
        // Arrange
        // The p upper bound is calculated with the formula below, when r = 8:
        // pBoundary = ((Math.pow(2,32))-1) * (32.0/(r * 128)), where pBoundary = 134217727.96875;
        Map<Integer, Integer> costParameters = [8:134217729, 128:8388608, 4096: 0]

        // Act and Assert
        costParameters.entrySet().forEach(entry -> {
            assertFalse(ScryptCipherProvider.isPValid(entry.getKey(), entry.getValue()))
        })
    }

    @Test
    void testShouldVerifyRValue() throws Exception {
        // Arrange
        final int r = 8

        // Act
        boolean valid = ScryptCipherProvider.isRValid(r)

        // Assert
        assertTrue(valid)
    }

    @Test
    void testShouldFailRValue() throws Exception {
        // Arrange
        final int r = 0

        // Act
        boolean valid = ScryptCipherProvider.isRValid(r)

        // Assert
        assertFalse(valid)
    }

    @Test
    void testShouldValidateScryptCipherProviderPBoundary() throws Exception {
        // Arrange
        final int n = 64
        final int r = 8
        final int p = 1

        // Act
        ScryptCipherProvider testCipherProvider = new ScryptCipherProvider(n, r, p)

        // Assert
        assertNotNull(testCipherProvider)
    }

    @Test
    void testShouldCatchInvalidP() throws Exception {
        // Arrange
        final int n = 64
        final int r = 8
        final int p = 0

        // Act
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> new ScryptCipherProvider(n, r, p))
        logger.warn(iae.getMessage())

        // Assert
        assertTrue(iae.getMessage().contains("Invalid p value exceeds p boundary"))
    }

    @Test
    void testShouldCatchInvalidR() throws Exception {
        // Arrange
        final int n = 64
        final int r = 0
        final int p = 0

        // Act
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> new ScryptCipherProvider(n, r, p))
        logger.warn(iae.getMessage())

        // Assert
        assertTrue(iae.getMessage().contains("Invalid r value; must be greater than 0"))
    }

    @Test
    void testShouldAcceptFormattedSaltWithPlus() throws Exception {
        // Arrange
        final String FULL_SALT_WITH_PLUS = "\$s0\$e0801\$smJD8vwWI3+uQCHYz2yg0+"

        // Act
        boolean isScryptSalt = ScryptCipherProvider.isScryptFormattedSalt(FULL_SALT_WITH_PLUS)
        logger.info("Is Scrypt salt: ${isScryptSalt}")

        // Assert
        assertTrue(isScryptSalt)
    }

    @EnabledIfSystemProperty(named = "nifi.test.unstable", matches = "true",
        disabledReason = "This test can be run on a specific machine to evaluate if the default parameters are sufficient")
    @Test
    void testDefaultConstructorShouldProvideStrongParameters() {
        // Arrange
        ScryptCipherProvider testCipherProvider = new ScryptCipherProvider()

        /** See this Stack Overflow answer for a good visualization of the interplay between N, r, p <a href="http://stackoverflow.com/a/30308723" rel="noopener">http://stackoverflow.com/a/30308723</a> */

        // Act
        int n = testCipherProvider.getN()
        int r = testCipherProvider.getR()
        int p = testCipherProvider.getP()
        logger.info("Default parameters N=${n}, r=${r}, p=${p}")

        // Calculate the parameters to reach 500 ms
        def (int minimumN, int minimumR, int minimumP) = calculateMinimumParameters(r, p)
        logger.info("Determined minimum safe parameters to be N=${minimumN}, r=${minimumR}, p=${minimumP}")

        // Assert
        assertTrue(n >= minimumN, "The default parameters for ScryptCipherProvider are too weak. Please update the default values to a stronger level.")
    }

    /**
     * Returns the parameters required for a derivation to exceed 500 ms on this machine. Code adapted from http://security.stackexchange.com/questions/17207/recommended-of-rounds-for-bcrypt
     *
     * @param r the block size in bytes (defaults to 8)
     * @param p the parallelization factor (defaults to 1)
     * @param maxHeapSize the maximum heap size to use in bytes (defaults to 1 GB)
     *
     * @return the minimum scrypt parameters as [N, r, p]
     */
    private static List<Integer> calculateMinimumParameters(int r = 8, int p = 1, int maxHeapSize = 1024 * 1024 * 1024) {
        // High start-up cost, so run multiple times for better benchmarking
        final int RUNS = 10

        // Benchmark using N=2^4
        int n = 2**4
        int dkLen = 128

        assertTrue(Scrypt.calculateExpectedMemory(n, r, p) <= maxHeapSize)

        byte[] salt = new byte[Scrypt.defaultSaltLength]
        new SecureRandom().nextBytes(salt)

        // Run once to prime the system
        double duration = time {
            Scrypt.scrypt(MICROBENCHMARK, salt, n, r, p, dkLen)
        }
        logger.info("First run of N=${n}, r=${r}, p=${p} took ${duration} ms (ignored)")

        def durations = []

        RUNS.times { int i ->
            duration = time {
                Scrypt.scrypt(MICROBENCHMARK, salt, n, r, p, dkLen)
            }
            logger.info("N=${n}, r=${r}, p=${p} took ${duration} ms")
            durations << duration
        }

        duration = durations.sum() / durations.size()
        logger.info("N=${n}, r=${r}, p=${p} averaged ${duration} ms")

        // Doubling N would double the run time
        // Keep increasing N until the estimated duration is over 500 ms
        while (duration < 500) {
            n *= 2
            duration *= 2
        }

        logger.info("Returning N=${n}, r=${r}, p=${p} for ${duration} ms")

        return [n, r, p]
    }

    private static double time(Closure c) {
        long start = System.nanoTime()
        c.call()
        long end = System.nanoTime()
        return (end - start) / 1_000_000.0
    }
}