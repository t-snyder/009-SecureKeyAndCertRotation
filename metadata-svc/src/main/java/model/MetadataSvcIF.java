package model;

public interface MetadataSvcIF
{
  // Event Bus
  public static final String EventBusKyberResponse = "kyber.keyResponse";

  
  // MetadataService Config keys
  public static final String PulsarUseTLS       = "pulsarUseTLS";
  public static final String PulsarUrls         = "pulsarUrl";
  public static final String PulsarClientSecret = "tlsSecret";
  public static final String PulsarCACertPath   = "caCertFilePath";
  
}
