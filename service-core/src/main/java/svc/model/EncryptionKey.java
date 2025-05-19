package svc.model;

import java.time.Instant;

/**
 * Represents an encryption key
 */
public class EncryptionKey 
{
  private final String id;
  private final byte[] keyBytes;
  private final String algorithm;
  private final Instant createdAt;
    
  public EncryptionKey( String id, byte[] keyBytes, String algorithm, Instant createdAt )
  {
    this.id        = id;
    this.keyBytes  = keyBytes;
    this.algorithm = algorithm;
    this.createdAt = createdAt;
  }
    
  public String  getId()        { return id;        }
  public byte[]  getKeyData()   { return keyBytes;  }
  public String  getAlgorithm() { return algorithm; }
  public Instant getCreatedAt() { return createdAt; }
}