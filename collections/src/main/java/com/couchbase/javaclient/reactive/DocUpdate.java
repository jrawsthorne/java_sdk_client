package com.couchbase.javaclient.reactive;

import static com.couchbase.client.java.kv.MutateInSpec.insert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.UUID;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.ReactiveCollection;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.manager.collection.ScopeSpec;
import com.couchbase.javaclient.doc.DocSpec;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class DocUpdate implements Callable<String> {
	private DocSpec ds;
	private Bucket bucket;
	private Collection collection;
	private static int num_docs = 0;
	private boolean done = false;

	public DocUpdate(DocSpec _ds, Bucket _bucket) {
		ds = _ds;
		bucket = _bucket;
	}

	public DocUpdate(DocSpec _ds, Collection _collection) {
		ds = _ds;
		collection = _collection;
	}

	@Override
	public String call() throws Exception {
		if (collection != null) {
			updateCollection(ds, collection);
		} else {
			updateBucketCollections(ds, bucket);
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
		System.out.println("Started update..");
		try {
			docsToUpdate.publishOn(Schedulers.elastic())
					// .delayElements(Duration.ofMillis(5))
					.flatMap(key -> rcollection.mutateIn(key,
							Arrays.asList(insert("UUID", UUID.randomUUID().toString()))))
					// Num retries, first backoff, max backoff
					.retryBackoff(10, Duration.ofMillis(100), Duration.ofMillis(1000))
					// Block until last value, complete or timeout expiry
					.blockLast(Duration.ofMinutes(10));
		} catch (Exception err) {
			err.printStackTrace();
		}
		System.out.println("Completed update");
	}
}