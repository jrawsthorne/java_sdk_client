package com.couchbase.javaclient;

import java.time.Duration;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.core.env.CoreEnvironment.Builder;
import com.couchbase.client.core.env.*;
import com.couchbase.client.core.deps.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;


public class ConnectionFactory {

	private final static Logger log = Logger.getLogger("com.couchbase.client");

	private ClusterEnvironment environment;
	private Cluster cluster;
	private Bucket bucket;
	private Collection collection;

	public ConnectionFactory(String clusterName, String username, String password, boolean secureConnection, boolean capella,String bucketName, String scopeName,
			String collectionName, Level logLevel) {
		log.setLevel(logLevel);
		this.setCluster(connectCluster(clusterName, username, password, secureConnection,capella));
		this.setBucket(connectBucket(cluster, bucketName));
		this.setCollection(connectCollection(bucket, scopeName, collectionName));
	}

	private Bucket connectBucket(Cluster cluster, String bucketName) {
		try {
			bucket = cluster.bucket(bucketName);
			bucket.waitUntilReady(Duration.ofSeconds(60));
		} catch (Exception ex) {
			this.handleException("Cannot connect to bucket " + bucketName + "\n" + ex);
		}
		return bucket;
	}
	
	
	private Cluster connectCluster(String clusterName, String username, String password, boolean secureConnection,boolean capella) {
		try {
			if(capella){
				environment = ClusterEnvironment.builder()
						.compressionConfig(CompressionConfig.create().enable(true))
						.timeoutConfig(TimeoutConfig
								.kvTimeout(Duration.ofSeconds(60))
								.queryTimeout(Duration.ofSeconds(100))
								.searchTimeout(Duration.ofSeconds(100))
								.analyticsTimeout(Duration.ofSeconds(100)))
						.securityConfig(SecurityConfig.enableTls(true)
								.trustManagerFactory(InsecureTrustManagerFactory.INSTANCE))
						.ioConfig(IoConfig.numKvConnections(2)
								.enableDnsSrv(true))
						.build();

				// Initialize the Connection
				cluster = Cluster.connect(clusterName,
						ClusterOptions.clusterOptions(username, password).environment(environment));

			}else if (secureConnection) {
				//TODO: root, x509 cert
				environment = ClusterEnvironment.builder()
						.compressionConfig(CompressionConfig.create().enable(true))
						.timeoutConfig(TimeoutConfig
								.kvTimeout(Duration.ofSeconds(60))
								.queryTimeout(Duration.ofSeconds(100))
								.searchTimeout(Duration.ofSeconds(100))
								.analyticsTimeout(Duration.ofSeconds(100)))
						.ioConfig(IoConfig.numKvConnections(2))
						.securityConfig(SecurityConfig.enableTls(true)
										.trustManagerFactory(InsecureTrustManagerFactory.INSTANCE))							
						.build();
			} else {
				environment = ClusterEnvironment.builder()
						.compressionConfig(CompressionConfig.create().enable(true))
						.timeoutConfig(TimeoutConfig
								.kvTimeout(Duration.ofSeconds(60))
								.queryTimeout(Duration.ofSeconds(100))
								.searchTimeout(Duration.ofSeconds(100))
								.analyticsTimeout(Duration.ofSeconds(100)))
						.ioConfig(IoConfig.numKvConnections(2))
						.build();
			}
			cluster = Cluster.connect(clusterName,
					ClusterOptions.clusterOptions(username, password).environment(environment));
			cluster.waitUntilReady(Duration.ofSeconds(60));
			environment.eventBus().subscribe(event -> {
				if (event.severity() == Event.Severity.ERROR) {
					log.error("Hit unrecoverable error..exiting \n" + event);
					System.exit(1);
				}
			});
		} catch (Exception ex) {
			this.handleException("Cannot connect to cluster " + clusterName + "\n" + ex);
		}
		return cluster;
	}



	private Collection connectCollection(Bucket bucket, String scopeName, String collectionName) {
		try {
			if (collectionName.equalsIgnoreCase("default")) {
				return bucket.defaultCollection();
			}
			if (scopeName != null) {
				return bucket.scope(scopeName).collection(collectionName);
			}
		} catch (Exception ex) {
			this.handleException(
					"Cannot connect to collection " + bucket + '.' + scopeName + '.' + collectionName + "\n" + ex);
		}
		return bucket.collection(collectionName);
	}

	public void close() {
		if (cluster != null) {
			cluster.disconnect();
		}
		if (environment != null) {
			environment.shutdown();
		}
	}

	public Bucket getBucket() {
		return bucket;
	}

	public Cluster getCluster() {
		return cluster;
	}

	public Collection getCollection() {
		return collection;
	}

	public void setBucket(Bucket bucket) {
		this.bucket = bucket;
	}

	public void setCluster(Cluster cluster) {
		this.cluster = cluster;
	}

	public void setCollection(Collection collection) {
		this.collection = collection;
	}

	public void handleException(String msg) {
		log.error(msg);
		this.close();
		System.exit(1);
	}
}
