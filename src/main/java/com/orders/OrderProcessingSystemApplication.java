package com.orders;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderProcessingSystemApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });

        configureKafkaSaslAndSsl();

        SpringApplication.run(OrderProcessingSystemApplication.class, args);
    }

    private static void configureKafkaSaslAndSsl() {
        String existingJaas = getEnvOrProperty("KAFKA_SASL_JAAS_CONFIG");
        if (existingJaas == null || existingJaas.isBlank()) {
            String username = getEnvOrProperty("KAFKA_USERNAME");
            String password = getEnvOrProperty("KAFKA_PASSWORD");
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String mechanism = getEnvOrProperty("KAFKA_SASL_MECHANISM");
                String loginModule = (mechanism != null && mechanism.toUpperCase().startsWith("SCRAM"))
                        ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                        : "org.apache.kafka.common.security.plain.PlainLoginModule";

                String jaasConfig = String.format(
                        "%s required username=\"%s\" password=\"%s\";",
                        loginModule, username, password
                );
                System.setProperty("KAFKA_SASL_JAAS_CONFIG", jaasConfig);
            }
        }

        String protocol = getEnvOrProperty("KAFKA_SECURITY_PROTOCOL");
        if (protocol != null && protocol.toUpperCase().contains("SSL")) {
            String caCert = getEnvOrProperty("KAFKA_CA_CERT");
            if (caCert != null && !caCert.isBlank()) {
                String normalizedPem = caCert.replace("\\n", "\n").trim();
                System.setProperty("spring.kafka.properties.ssl.truststore.type", "PEM");
                System.setProperty("spring.kafka.properties.ssl.truststore.certificates", normalizedPem);
            } else {
                System.setProperty(
                        "spring.kafka.properties.ssl.engine.factory.class",
                        "com.orders.config.KafkaSslEngineFactory"
                );
            }
        }
    }

    private static String getEnvOrProperty(String key) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : System.getProperty(key);
    }
}
