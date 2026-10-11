package com.orders.config;

import org.apache.kafka.common.security.auth.SslEngineFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Custom Kafka {@link SslEngineFactory} used when connecting to managed Kafka brokers
 * (such as Aiven) that use a project-specific/self-signed CA certificate without a
 * custom truststore configured.
 */
public class KafkaSslEngineFactory implements SslEngineFactory {

    private SSLContext sslContext;

    @Override
    public void configure(Map<String, ?> configs) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(new KeyManager[0], trustAllCerts, new SecureRandom());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Kafka SSL context", e);
        }
    }

    @Override
    public SSLEngine createClientSslEngine(String peerHost, int peerPort, String endpointIdentification) {
        SSLEngine engine = sslContext.createSSLEngine(peerHost, peerPort);
        engine.setUseClientMode(true);
        SSLParameters sslParams = engine.getSSLParameters();
        // Disable hostname endpoint verification when using self-signed/project CAs without truststore
        sslParams.setEndpointIdentificationAlgorithm("");
        engine.setSSLParameters(sslParams);
        return engine;
    }

    @Override
    public SSLEngine createServerSslEngine(String peerHost, int peerPort) {
        SSLEngine engine = sslContext.createSSLEngine(peerHost, peerPort);
        engine.setUseClientMode(false);
        return engine;
    }

    @Override
    public boolean shouldBeRebuilt(Map<String, Object> nextConfigs) {
        return false;
    }

    @Override
    public Set<String> reconfigurableConfigs() {
        return Collections.emptySet();
    }

    @Override
    public KeyStore keystore() {
        return null;
    }

    @Override
    public KeyStore truststore() {
        return null;
    }

    @Override
    public void close() {
    }
}
