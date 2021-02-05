package com.couchbase.javaclient.reactive;

import static com.couchbase.client.java.kv.UpsertOptions.upsertOptions;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.HashMap;
import java.util.Map;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.ReactiveCollection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.manager.collection.ScopeSpec;
import com.couchbase.client.java.codec.RawBinaryTranscoder;
import com.couchbase.javaclient.doc.*;
import com.couchbase.javaclient.utils.FileUtils;


import org.apache.log4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class DocUpdate implements Callable<String> {

	private final static Logger log = Logger.getLogger(DocUpdate.class);

	private DocSpec ds;
	private Bucket bucket;
	private Collection collection;
	private static int num_docs = 0;
	private boolean done = false;
	private List<String> fieldsToUpdate;
	private Map<String, String> elasticMap = new HashMap<>();

	public DocUpdate(DocSpec _ds, Bucket _bucket, List<String> fieldsToUpdate) {
		ds = _ds;
		bucket = _bucket;
		this.fieldsToUpdate = fieldsToUpdate;
	}

	public DocUpdate(DocSpec _ds, Collection _collection, List<String> fieldsToUpdate) {
		ds = _ds;
		collection = _collection;
		this.fieldsToUpdate = fieldsToUpdate;
	}

	@Override
	public String call() throws Exception {
		if (collection != null) {
			log.info("Update collection " + collection.bucketName() + "." + collection.scopeName() + "." + collection.name());
			updateCollection(ds, collection);
		} else {
			log.info("Update bucket collections");
			updateBucketCollections(ds, bucket);
		}
		// upsert to elastic
		if (ds.isElasticSync() && !elasticMap.isEmpty()) {
			final File elasticFile = FileUtils.writeForElastic(elasticMap, ds.get_template(), "update");
			ElasticSync.sync(ds.getElasticIP(), ds.getElasticPort(), ds.getElasticLogin(), ds.getElasticPassword(), elasticFile, 5);
		}
		done = true;
		return num_docs + " DOCS UPDATED!";
	}

	public void updateBucketCollections(DocSpec ds, Bucket bucket) {
		List<Collection> bucketCollections = new ArrayList<>();
		List<ScopeSpec> bucketScopes = bucket.collections().getAllScopes();
		for (ScopeSpec scope : bucketScopes) {
			for (CollectionSpec scopeCollection : scope.collections()) {
				Collection collection = bucket.scope(scope.name()).collection(scopeCollection.name());
				if (collection != null) {
					bucketCollections.add(collection);
				}
			}
		}
		bucketCollections.parallelStream().forEach(c -> update(ds, c));
	}

	public void updateCollection(DocSpec ds, Collection collection) {
		update(ds, collection);
	}

	public void update(DocSpec ds, Collection collection) {
		ReactiveCollection rcollection = collection.reactive();
		num_docs = (int) (ds.get_num_ops() * ((float) ds.get_percent_update() / 100));
		Flux<String> docsToUpdate = Flux.range(ds.get_startSeqNum(), num_docs)
				.map(id -> ds.get_prefix() + id + ds.get_suffix());
		if(ds.get_shuffle_docs()){
			List<String> docs = docsToUpdate.collectList().block();
			java.util.Collections.shuffle(docs);
			docsToUpdate = Flux.fromIterable(docs);
		}
		List<MutationResult> results;
		try {
			if ("Binary".equals(ds.get_template())) {
				results = docsToUpdate.publishOn(Schedulers.elastic())
						.flatMap(key -> rcollection.upsert(key, new Binary().createBinaryObject(ds.faker, ds.get_size()),
								upsertOptions().transcoder(RawBinaryTranscoder.INSTANCE)
								.expiry(Duration.ofSeconds(ds.get_expiry()))))
						.buffer(1000)
						.blockLast(Duration.ofSeconds(num_docs));
			}else {
				DocTemplate docTemplate = DocTemplateFactory.getDocTemplate(ds);
				results = docsToUpdate.publishOn(Schedulers.elastic())
						.flatMap(key -> rcollection.upsert(key, getObject(key, docTemplate, elasticMap, collection),
								upsertOptions().expiry(Duration.ofSeconds(ds.get_expiry()))))
						.buffer(1000)
						.blockLast(Duration.ofSeconds(num_docs));
			}
			// print results
			if (ds.isOutput()) {
				System.out.println("Update results");
				FileUtils.printMutationResults(results, log);
			}
		} catch (Throwable err) {
			log.error(err);
		}
		log.info("Completed update");
	}

	private JsonObject getObject(String key, DocTemplate docTemplate, Map<String, String> elasticMap, Collection collection) {
		JsonObject obj = docTemplate.updateJsonObject(collection.get(key).contentAsObject(), fieldsToUpdate);
		elasticMap.put(key, obj.toString());
		return obj;
	}
}