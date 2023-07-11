/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.python.transform;

import org.apache.commons.lang.time.DateUtils;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateExtensions;
import sun.security.x509.CertificateIssuerName;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateSubjectName;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.GeneralName;
import sun.security.x509.GeneralNames;
import sun.security.x509.IPAddressName;
import sun.security.x509.SubjectAlternativeNameExtension;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/**
 * Utility class with methods for generating a X.509 self signed certificate
 * and creating a Java key store with a self signed certificate.
 */
public final class KeyStores {

  public static final String SSL_KEYSTORE_TYPE = "JKS";

  private static final String KEY_PAIR_ALGORITHM = "RSA";
  private static final String SECURE_RANDOM_ALGORITHM = "SHA1PRNG";
  private static final String SECURE_RANDOM_PROVIDER = "SUN";

  /* This is based on https://www.ietf.org/rfc/rfc1779.txt
     CN      CommonName
     L       LocalityName
     ST      StateOrProvinceName
     O       OrganizationName
     OU      OrganizationalUnitName
     C       CountryName
     STREET  StreetAddress

     All fields are not required.
    */
  static final String DISTINGUISHED_NAME = "CN=localhost, L=Palo Alto, C=US";
  static final String SIGNATURE_ALGORITHM = "SHA1withRSA";
  static final String CERT_ALIAS = "cert";

  private static final String BEGIN_PRIVATE_KEY_LINE = "-----BEGIN RSA PRIVATE KEY-----\n";
  private static final String END_PRIVATE_KEY_LINE = "-----END RSA PRIVATE KEY-----\n";
  private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n";
  private static final String END_CERTIFICATE = "-----END CERTIFICATE-----\n";

  private static final int KEY_SIZE = 2048;
  private static final int VALIDITY = 999;

  /* private constructor */
  private KeyStores() {}

  public static KeyStore generatedCertKeyStore(int validityDays, String password) {
    return generatedCertKeyStore(validityDays, password, DISTINGUISHED_NAME);
  }

  /**
   * Create a Java key store with a stored self-signed certificate.
   *
   * @param validityDays number of days that the cert will be valid for
   * @param password the password to protect the generated key store
   * @param distinguishedName distinguished name for the owner of the certificate
   * @return Java keystore which has a self signed X.509 certificate
   */
  public static KeyStore generatedCertKeyStore(int validityDays, String password, String distinguishedName) {
    try {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_PAIR_ALGORITHM);
      SecureRandom random = SecureRandom.getInstance(SECURE_RANDOM_ALGORITHM, SECURE_RANDOM_PROVIDER);
      keyGen.initialize(KEY_SIZE, random);
      // generate a key pair
      KeyPair pair = keyGen.generateKeyPair();

      X509Certificate cert = getCertificate(distinguishedName, pair, validityDays, SIGNATURE_ALGORITHM);

      KeyStore keyStore = KeyStore.getInstance(SSL_KEYSTORE_TYPE);
      keyStore.load(null, password.toCharArray());
      keyStore.setKeyEntry(CERT_ALIAS, pair.getPrivate(), password.toCharArray(),
                           new java.security.cert.Certificate[]{cert});
      return keyStore;
    } catch (Exception e) {
      throw new RuntimeException("SSL is enabled but a key store file could not be created. A keystore is required " +
                                   "for SSL to be used.", e);
    }
  }

  /**
   * Generate an X.509 certificate
   *
   * @param dn Distinguished name for the owner of the certificate, it will also be the signer of the certificate.
   * @param pair Key pair used for signing the certificate.
   * @param days Validity of the certificate.
   * @param algorithm Name of the signature algorithm used.
   * @return A X.509 certificate
   */
  private static X509Certificate getCertificate(String dn, KeyPair pair, int days, String algorithm) throws IOException,
    CertificateException, NoSuchProviderException, NoSuchAlgorithmException, InvalidKeyException, SignatureException {
    // Calculate the validity interval of the certificate
    GeneralNames generalNames = new GeneralNames();
    generalNames.add(new GeneralName(new IPAddressName("127.0.0.1")));
    CertificateExtensions ext = new CertificateExtensions();
    ext.set(SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension(generalNames));
    Date from = new Date();
    Date to = DateUtils.addDays(from, days);
    CertificateValidity interval = new CertificateValidity(from, to);
    // Generate a random number to use as the serial number for the certificate
    BigInteger sn = new BigInteger(64, new SecureRandom());
    // Create the name of the owner based on the provided distinguished name
    X500Name owner = new X500Name(dn);
    // Create an info objects with the provided information, which will be used to create the certificate
    X509CertInfo info = new X509CertInfo();
    info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
    info.set(X509CertInfo.VALIDITY, interval);
    info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));
    info.set(X509CertInfo.EXTENSIONS, ext);
    // In java 7, subject is of type CertificateSubjectName and issuer is of type CertificateIssuerName.
    // These were changed to X500Name in Java8. So looking at the field type before setting them.
    // This certificate will be self signed, hence the subject and the issuer are same.
    Field subjectField = null;
    try {
      subjectField = info.getClass().getDeclaredField("subject");
      if (subjectField.getType().equals(X500Name.class)) {
        info.set(X509CertInfo.SUBJECT, owner);
        info.set(X509CertInfo.ISSUER, owner);
      } else {
        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
      }
    } catch (NoSuchFieldException e) {
      // Trying to set it to Java 8 types. If one of the underlying fields has changed then this will throw a
      // CertificateException which is handled by the caller.
      info.set(X509CertInfo.SUBJECT, owner);
      info.set(X509CertInfo.ISSUER, owner);
    }
    info.set(X509CertInfo.KEY, new CertificateX509Key(pair.getPublic()));
    AlgorithmId algo = new AlgorithmId(AlgorithmId.sha1WithRSAEncryption_oid);
    info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));
    // Create the certificate and sign it with the private key
    X509CertImpl cert = new X509CertImpl(info);
    PrivateKey privateKey = pair.getPrivate();
    cert.sign(privateKey, algorithm);
    return cert;
  }

  /**
   * Create pem file from java keystore which contains private key and certificate
   *
   * @param keyStore instance of java KeyStore
   * @param password password needed to get private key from keystore
   * @param outputFile file to write a pem file to
   */
  public static void generatePemFileFromKeyStore(KeyStore keyStore, String password, File outputFile) throws
    UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException,
    CertificateEncodingException, IOException {

    String encodedString = BEGIN_PRIVATE_KEY_LINE;
    encodedString += Base64.getEncoder().encodeToString(keyStore.getKey("cert", password.toCharArray()).getEncoded());
    encodedString += "\n" + END_PRIVATE_KEY_LINE;

    encodedString += BEGIN_CERTIFICATE;
    encodedString += Base64.getEncoder().encodeToString(keyStore.getCertificate("cert").getEncoded()) + "\n";
    encodedString += END_CERTIFICATE;

    // set 600 permissions for pem file before writting anything
    outputFile.createNewFile();
    outputFile.setReadable(false, false);
    outputFile.setReadable(true, true);
    outputFile.setWritable(false, false);
    outputFile.setWritable(true, true);

    try (PrintWriter out = new PrintWriter(outputFile)) {
      out.println(encodedString);
    }
  }
}
