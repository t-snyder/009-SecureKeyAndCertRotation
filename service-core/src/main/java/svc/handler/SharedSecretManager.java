package svc.handler;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.FileSystem;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe shared secret manager for Vert.x applications with persistent
 * storage. Manages encryption/decryption keys accessible across multiple
 * verticles. Persists secrets to a file-based storage backed by Kubernetes PVC.
 * 
 * The path is obtained from the deployment or statefulset manifest as an env 
 * variable with the name SHARED_SECRET_PATH. This is defined as a static final String below.
 * 
 * Uses a LocalMap<String, String> as the Map is transformed into a Json Object.
 */
public class SharedSecretManager
{
  private static final Logger LOGGER           = LoggerFactory.getLogger( SharedSecretManager.class.getName() );
  private static final String SHARED_MAP_NAME  = "shared.secrets";
  private static final String WORKER_POOL_NAME = "secret-manager-worker";
  private static final int    WORKER_POOL_SIZE = 10;
  private static final String ENV_PERSIST_PATH = "SHARED_SECRET_PATH";
  
  private final FileSystem               fileSystem;
  private final SharedData               sharedData;
  private final LocalMap<String, String> secretsMap;
  private final String                   persistenceFilePath;
  private final Object                   fileLock = new Object();
  private final WorkerExecutor           workerExecutor;

  
  /**
   * Creates a SharedSecretManager with a set persistence path.  
   * 
   * @param vertx
   *          the Vert.x instance
   * @param persistenceFilePath
   *          path to the persistence file (should be on PVC mount)
   */
  public SharedSecretManager( Vertx vertx )
  {
    this.fileSystem          = vertx.fileSystem();
    this.sharedData          = vertx.sharedData();
    this.secretsMap          = sharedData.getLocalMap( SHARED_MAP_NAME );
    this.workerExecutor      = vertx.createSharedWorkerExecutor( WORKER_POOL_NAME, WORKER_POOL_SIZE );

    // Obtain path from the deployment env manifest
    this.persistenceFilePath = System.getenv( ENV_PERSIST_PATH );
 
    // Initialize by loading from persistent storage
    loadSecretsFromFile();
  }

  /**
   * Stores a secret key (byte array) for the given service ID.
   * 
   * @param serviceId - the unique identifier for the service
   * @param secretKey - the secret key as byte array
   * @return Future that completes when the secret is stored and persisted
   */
  public Future<Void> putSecretBytes( String serviceId, byte[] secretKey )
  {
    return workerExecutor.executeBlocking( () -> 
    {
      validateServiceId( serviceId );
      if( secretKey == null || secretKey.length == 0 )
      {
        throw new IllegalArgumentException( "Secret key bytes cannot be null or empty" );
      }

      String encodedKey = Base64.getEncoder().encodeToString( secretKey );
      secretsMap.put( serviceId, encodedKey );
      LOGGER.info( "Secret bytes stored in memory for service: " + serviceId );
      return null;
    } ).compose( v -> persistSecretsToFile() ).onSuccess( v -> LOGGER.info( "Secret bytes persisted to file for service: " + serviceId ) ).onFailure( e -> LOGGER.error( "Failed to store secret bytes for service: " + serviceId, e ) );
  }


  /**
   * Retrieves a shared secret key as byte array for the given service ID.
   * 
   * @param serviceId - the unique identifier for the service
   * @return Future containing the secret key as byte array or empty if not found
   */
  public byte[] getSecretBytes( String serviceId )
   throws Exception
  {
    validateServiceId( serviceId );
    String secret = secretsMap.get( serviceId );

    if( secret == null )
      throw new Exception( "Shared secret not found for serviceId = " + serviceId );
    
    byte[] secretBytes = Base64.getDecoder().decode( secret );
    return secretBytes;
  }

  /**
   * Removes a secret key for the given service ID.
   * 
   * @param serviceId
   *          the unique identifier for the service
   * @return Future that completes when the secret is removed and persisted
   */
  public Future<Boolean> removeSecret( String serviceId )
  {
    return workerExecutor.executeBlocking( () -> 
    {
      validateServiceId( serviceId );
      String removed = secretsMap.remove( serviceId );
      boolean wasRemoved = removed != null;

      if( wasRemoved )
      {
        LOGGER.info( "Secret removed from memory for service: " + serviceId );
      }
      return wasRemoved;
    } ).compose( wasRemoved -> {
      if( wasRemoved )
      {
        return persistSecretsToFile().map( wasRemoved ).onSuccess( v -> LOGGER.info( "Secret removal persisted to file for service: " + serviceId ) );
      } else
      {
        return Future.succeededFuture( false );
      }
    } ).onFailure( e -> LOGGER.error("Failed to remove secret for service: " + serviceId, e ) );
  }

  /**
   * Lists all service IDs that have stored secrets.
   * 
   * @return Future containing set of service IDs
   */
  public Future<Set<String>> listServiceIds()
  {
    return workerExecutor.executeBlocking( () -> 
    {
      Set<String> serviceIds = secretsMap.keySet();
      return Set.copyOf( serviceIds );
    } ).onFailure( e -> LOGGER.error( "Failed to list service IDs", e ) );
  }

  /**
   * Clears all stored secrets from memory and persistent storage.
   * 
   * @return Future that completes when all secrets are cleared
   */
  public Future<Void> clearAll()
  {
    return workerExecutor.executeBlocking( () -> {
      int size = secretsMap.size();
      secretsMap.clear();
      LOGGER.info( "Cleared " + size + " secrets from memory" );
      return size;
    } ).compose( size -> persistSecretsToFile() ).onSuccess( v -> LOGGER.info( "All secrets cleared from memory and storage" ) )
                                                 .onFailure( e -> LOGGER.info( "Failed to clear secrets", e ) );
  }

  /**
   * Manually reload secrets from persistent storage.
   * 
   * @return Future that completes when secrets are reloaded
   */
  public Future<Object> reloadSecrets()
  {
    return loadSecretsFromFile();
  }

  /**
   * Closes the worker executor and releases resources. Should be called when
   * the SharedSecretManager is no longer needed.
   * 
   * @return Future that completes when resources are released
   */
  public Future<Void> close()
  {
    return workerExecutor.close();
  }

  private Future<Object> loadSecretsFromFile()
  {
    return workerExecutor.executeBlocking( () -> 
    {
      synchronized( fileLock )
      {
        return fileSystem.exists( persistenceFilePath ).compose( exists -> 
        {
          if( exists )
          {
            return fileSystem.readFile( persistenceFilePath ).compose( buffer -> 
            {
              JsonObject json = new JsonObject( buffer.toString() );
              secretsMap.clear();

              for( String key : json.fieldNames() )
              {
                secretsMap.put( key, json.getString( key ) );
              }

              LOGGER.info( "Loaded " + secretsMap.size() + " secrets from persistent storage" );
              return Future.succeededFuture();
            } ).recover( error -> 
            {
              LOGGER.error( "Failed to read secrets file, starting with empty secrets", error );
              return Future.succeededFuture();
            } );
          } 
          else
          {
            LOGGER.info( "Secrets file does not exist, starting with empty secrets" );
            return Future.succeededFuture();
          }
        } ).recover( error -> {
          LOGGER.error( "Failed to check secrets file existence", error );
          return Future.succeededFuture();
        } );
      }
    } ).compose( v -> v );
  }

  private Future<Void> persistSecretsToFile()
  {
    return workerExecutor.executeBlocking( () -> 
    {
      synchronized( fileLock )
      {
        LOGGER.info( "===================================================="  );
        JsonObject json = new JsonObject();
        for( String key : secretsMap.keySet() )
        {
          json.put( key, secretsMap.get( key ) );
          LOGGER.info( "SharedSecretManager.persistSecretsToFile - key = " + key + "; value = " + secretsMap.get( key ));
        }

        String jsonString = json.encodePrettily();

        // Ensure directory exists
        String      directory = persistenceFilePath.substring( 0, persistenceFilePath.lastIndexOf( '/' ) );
        CopyOptions options   = new CopyOptions().setReplaceExisting(true);
        return fileSystem.mkdirs( directory ).compose( v -> 
        {
          // Write to temporary file first, then rename for atomic operation
          String tempFile = persistenceFilePath + ".tmp";
          return fileSystem.writeFile( tempFile, io.vertx.core.buffer.Buffer.buffer( jsonString ) )
                           .compose( writeResult -> fileSystem.move( tempFile, persistenceFilePath, options ) );
        } );
      }
    } ).compose( v -> v ).onFailure( e -> LOGGER.error( "Failed to persist secrets to file. Error = " + e.getMessage(), e ) );
  }

  private void validateServiceId( String serviceId )
  {
    if( serviceId == null || serviceId.trim().isEmpty() )
    {
      throw new IllegalArgumentException( "Service ID cannot be null or empty" );
    }
  }
}