package svc.model;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WatcherConfig
{
  private static final Logger LOGGER = LoggerFactory.getLogger( WatcherConfig.class );

  private String watcherNames   = null;
  private String proxyTLSSecret = null;
  private String pulsarUseTLS   = null;
  private String pulsarURL      = null;

  // Derived attributes
  private String tlsCertPath   = null;
  
  
  public WatcherConfig( Map<String, String> data )
  {
    if( data.get( WatcherIF.WatcherNames        ) != null ) watcherNames   = data.get( WatcherIF.WatcherNames        );
    if( data.get( WatcherIF.ProxyCertSecretName ) != null ) proxyTLSSecret = data.get( WatcherIF.ProxyCertSecretName );
    if( data.get( WatcherIF.PulsarUseTLS        ) != null ) pulsarUseTLS   = data.get( WatcherIF.PulsarUseTLS        );
    if( data.get( WatcherIF.PulsarProxyURL      ) != null ) pulsarURL      = data.get( WatcherIF.PulsarProxyURL      );

    LOGGER.info( "***************** Pulsar Watcher Config is set for ******************" );
    LOGGER.info( WatcherIF.WatcherNames + "        = " + watcherNames);
    LOGGER.info( WatcherIF.ProxyCertSecretName + " = " + proxyTLSSecret );
    LOGGER.info( WatcherIF.PulsarUseTLS + "        = " + pulsarUseTLS );
    LOGGER.info( WatcherIF.PulsarProxyURL + "      = " + pulsarURL );
    LOGGER.info( "***************** End of Pulsar Watcher Config ******************" );
  }

  /**
   * Obtained via volume mount within pod, deployment or statefulset
   *
   * @param caCertPath
   * @param tlsCertPath
   */
  public void setTlsCertPath( String tlsCertPath )
  {
    this.tlsCertPath = tlsCertPath;
  }

  public String getWatcherNames()   { return watcherNames;   }
  public String getProxyTLSSecret() { return proxyTLSSecret; }
  public String getPulsarUseTLS()   { return pulsarUseTLS;   }
  public String getPulsarURL()      { return pulsarURL;      }
  public String getTlsCertPath()    { return tlsCertPath;    }
}
