package freenet.client.async;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.SplitfileBlock;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.SerializableToFieldSetBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class SplitFileInserterSegment implements PutCompletionCallback, FECCallback {

	private static volatile boolean logMINOR;

	final SplitFileInserter parent;

	final FECCodec splitfileAlgo;

	final Bucket[] dataBlocks;

	final Bucket[] checkBlocks;

	final ClientCHK[] dataURIs;

	final ClientCHK[] checkURIs;

	final SingleBlockInserter[] dataBlockInserters;

	final SingleBlockInserter[] checkBlockInserters;

	final InsertContext blockInsertContext;

	final int segNo;

	private volatile boolean encoded;
	
	private volatile boolean started;
	
	private volatile boolean finished;
	
	private volatile boolean hasURIs;

	private final boolean getCHKOnly;

	private InsertException toThrow;

	private final FailureCodeTracker errors;

	private int blocksGotURI;

	private int blocksCompleted;
	
	private final boolean persistent;

	public SplitFileInserterSegment(SplitFileInserter parent,
			FECCodec splitfileAlgo, Bucket[] origDataBlocks,
			InsertContext blockInsertContext, boolean getCHKOnly, int segNo, ObjectContainer container) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.persistent = parent.persistent;
		this.errors = new FailureCodeTracker(true);
		this.blockInsertContext = blockInsertContext;
		this.splitfileAlgo = splitfileAlgo;
		this.dataBlocks = origDataBlocks;
		int checkBlockCount = splitfileAlgo == null ? 0 : splitfileAlgo
				.countCheckBlocks();
		checkBlocks = new Bucket[checkBlockCount];
		checkURIs = new ClientCHK[checkBlockCount];
		dataURIs = new ClientCHK[origDataBlocks.length];
		dataBlockInserters = new SingleBlockInserter[dataBlocks.length];
		checkBlockInserters = new SingleBlockInserter[checkBlocks.length];
		parent.parent.addBlocks(dataURIs.length + checkURIs.length, container);
		parent.parent.addMustSucceedBlocks(dataURIs.length + checkURIs.length, container);
		this.segNo = segNo;
	}

	/**
	 * Resume an insert segment
	 * 
	 * @throws ResumeException
	 */
	public SplitFileInserterSegment(SplitFileInserter parent,
			SimpleFieldSet fs, short splitfileAlgorithm, InsertContext ctx,
			boolean getCHKOnly, int segNo, ClientContext context, ObjectContainer container) throws ResumeException {
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.persistent = parent.persistent;
		this.blockInsertContext = ctx;
		this.segNo = segNo;
		if (!"SplitFileInserterSegment".equals(fs.get("Type")))
			throw new ResumeException("Wrong Type: " + fs.get("Type"));
		finished = Fields.stringToBool(fs.get("Finished"), false);
		encoded = true;
		started = Fields.stringToBool(fs.get("Started"), false);
		SimpleFieldSet errorsFS = fs.subset("Errors");
		if (errorsFS != null)
			this.errors = new FailureCodeTracker(true, errorsFS);
		else
			this.errors = new FailureCodeTracker(true);
		if (finished && !errors.isEmpty())
			toThrow = InsertException.construct(errors);
		blocksGotURI = 0;
		blocksCompleted = 0;
		SimpleFieldSet dataFS = fs.subset("DataBlocks");
		if (dataFS == null)
			throw new ResumeException("No data blocks");
		String tmp = dataFS.get("Count");
		if (tmp == null)
			throw new ResumeException("No data block count");
		int dataBlockCount;
		try {
			dataBlockCount = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt data blocks count: " + e + " : "
					+ tmp);
		}

		hasURIs = true;

		dataBlocks = new Bucket[dataBlockCount];
		dataURIs = new ClientCHK[dataBlockCount];
		dataBlockInserters = new SingleBlockInserter[dataBlockCount];

		// Check blocks first, because if there are missing check blocks, we
		// need
		// all the data blocks so we can re-encode.

		SimpleFieldSet checkFS = fs.subset("CheckBlocks");
		if (checkFS != null) {
			tmp = checkFS.get("Count");
			if (tmp == null)
				throw new ResumeException(
						"Check blocks but no check block count");
			int checkBlockCount;
			try {
				checkBlockCount = Integer.parseInt(tmp);
			} catch (NumberFormatException e) {
				throw new ResumeException("Corrupt check blocks count: " + e
						+ " : " + tmp);
			}
			checkBlocks = new Bucket[checkBlockCount];
			checkURIs = new ClientCHK[checkBlockCount];
			checkBlockInserters = new SingleBlockInserter[checkBlockCount];
			for (int i = 0; i < checkBlockCount; i++) {
				String index = Integer.toString(i);
				SimpleFieldSet blockFS = checkFS.subset(index);
				if (blockFS == null) {
					hasURIs = false;
					encoded = false;
					Logger.normal(this, "Clearing encoded because block " + i
							+ " of " + segNo + " missing");
					continue;
				}
				tmp = blockFS.get("URI");
				if (tmp != null) {
					try {
						checkURIs[i] = (ClientCHK) ClientKey
								.getBaseKey(new FreenetURI(tmp));
						blocksGotURI++;
					} catch (MalformedURLException e) {
						throw new ResumeException("Corrupt URI: " + e + " : "
								+ tmp);
					}
				} else {
					hasURIs = false;
				}
				boolean blockFinished = Fields.stringToBool(blockFS
						.get("Finished"), false)
						&& checkURIs[i] != null;
				if (blockFinished && checkURIs[i] == null) {
					Logger.error(this, "No URI for check block " + i + " of "
							+ segNo + " yet apparently finished?");
					encoded = false;
				}
				// Read data; only necessary if the block isn't finished.
				if (!blockFinished) {
					SimpleFieldSet bucketFS = blockFS.subset("Data");
					if (bucketFS != null) {
						try {
							checkBlocks[i] = SerializableToFieldSetBucketUtil
									.create(bucketFS, context.random,
											ctx.persistentFileTracker);
							if (logMINOR)
								Logger.minor(this, "Check block " + i + " : "
										+ checkBlocks[i]);
						} catch (CannotCreateFromFieldSetException e) {
							Logger.error(this,
									"Failed to deserialize check block " + i
											+ " of " + segNo + " : " + e, e);
							// Re-encode it.
							checkBlocks[i] = null;
							encoded = false;
						}
						if (checkBlocks[i] == null)
							throw new ResumeException(
									"Check block "
											+ i
											+ " of "
											+ segNo
											+ " not finished but no data (create returned null)");
					}
					// Don't create fetcher yet; that happens in start()
				} else
					blocksCompleted++;
				if (checkBlocks[i] == null && checkURIs[i] == null) {
					Logger.normal(this, "Clearing encoded because block " + i
							+ " of " + segNo + " missing");
					encoded = false;
				}
				checkFS.removeSubset(index);
			}
			splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm,
					dataBlockCount, checkBlocks.length, context.mainExecutor);
			
			if(checkBlocks.length > dataBlocks.length) {
				// Work around 1135 bug.
				// FIXME remove
				throw new ResumeException("Detected 1135 insert bug, you must restart the insert");
			}
		} else {
			Logger.normal(this, "Not encoded because no check blocks");
			encoded = false;
			splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm,
					dataBlockCount, context.mainExecutor);
			int checkBlocksCount = splitfileAlgo.countCheckBlocks();
			this.checkURIs = new ClientCHK[checkBlocksCount];
			this.checkBlocks = new Bucket[checkBlocksCount];
			this.checkBlockInserters = new SingleBlockInserter[checkBlocksCount];
			hasURIs = false;
		}

		for (int i = 0; i < dataBlockCount; i++) {
			String index = Integer.toString(i);
			SimpleFieldSet blockFS = dataFS.subset(index);
			if (blockFS == null)
				throw new ResumeException("No data block " + i + " on segment "
						+ segNo);
			tmp = blockFS.get("URI");
			if (tmp != null) {
				try {
					dataURIs[i] = (ClientCHK) ClientKey
							.getBaseKey(new FreenetURI(tmp));
					blocksGotURI++;
				} catch (MalformedURLException e) {
					throw new ResumeException("Corrupt URI: " + e + " : " + tmp);
				}
			} else
				hasURIs = false;
			boolean blockFinished = Fields.stringToBool(
					blockFS.get("Finished"), false);
			if (blockFinished && dataURIs[i] == null)
				throw new ResumeException("Block " + i + " of " + segNo
						+ " finished but no URI");
			if (!blockFinished)
				finished = false;
			else
				blocksCompleted++;

			// Read data
			SimpleFieldSet bucketFS = blockFS.subset("Data");
			if (bucketFS == null) {
				if (!blockFinished)
					throw new ResumeException("Block " + i + " of " + segNo
							+ " not finished but no data");
				else if (splitfileAlgorithm > 0 && !encoded)
					throw new ResumeException("Block " + i + " of " + segNo
							+ " data not available even though not encoded");
			} else {
				try {
					dataBlocks[i] = SerializableToFieldSetBucketUtil.create(
							bucketFS, context.random, ctx.persistentFileTracker);
					if (logMINOR)
						Logger.minor(this, "Data block " + i + " : "
								+ dataBlocks[i]);
				} catch (CannotCreateFromFieldSetException e) {
					throw new ResumeException("Failed to deserialize block "
							+ i + " of " + segNo + " : " + e, e);
				}
				if (dataBlocks[i] == null)
					throw new ResumeException(
							"Block "
									+ i
									+ " of "
									+ segNo
									+ " could not serialize data (create returned null) from "
									+ bucketFS);
				// Don't create fetcher yet; that happens in start()
			}
			dataFS.removeSubset(index);
		}

		if (!encoded) {
			finished = false;
			hasURIs = false;
			for (int i = 0; i < dataBlocks.length; i++)
				if (dataBlocks[i] == null)
					throw new ResumeException("Missing data block " + i
							+ " and need to reconstruct check blocks");
		}
		parent.parent.addBlocks(dataURIs.length + checkURIs.length, container);
		parent.parent.addMustSucceedBlocks(dataURIs.length + checkURIs.length, container);
	}

	public synchronized SimpleFieldSet getProgressFieldset() {
		SimpleFieldSet fs = new SimpleFieldSet(false); // these get BIG
		fs.putSingle("Type", "SplitFileInserterSegment");
		fs.put("Finished", finished);
		// If true, check blocks which are null are finished
		fs.put("Encoded", encoded);
		// If true, data blocks which are null are finished
		fs.put("Started", started);
		fs.tput("Errors", errors.toFieldSet(false));
		SimpleFieldSet dataFS = new SimpleFieldSet(false);
		dataFS.put("Count", dataBlocks.length);
		for (int i = 0; i < dataBlocks.length; i++) {
			SimpleFieldSet block = new SimpleFieldSet(false);
			if (dataURIs[i] != null)
				block.putSingle("URI", dataURIs[i].getURI().toString());
			SingleBlockInserter sbi = dataBlockInserters[i];
			// If started, then sbi = null => block finished.
			boolean finished = started && sbi == null;
			if (started) {
				block.put("Finished", finished);
			}
			Bucket data = dataBlocks[i];
			if (data == null && finished) {
				// Ignore
				if (logMINOR)
					Logger.minor(this, "Could not save to disk bucket: null");
			} else if (data instanceof SerializableToFieldSetBucket) {
				SimpleFieldSet tmp = ((SerializableToFieldSetBucket) data).toFieldSet();
				if (tmp == null) {
					if (logMINOR)
						Logger.minor(this, "Could not save to disk data: " + data);
					return null;
				}
				block.put("Data", tmp);
			} else {
				if (logMINOR)
					Logger.minor(this,
							"Could not save to disk (not serializable to fieldset): " + data);
				return null;
			}
			if (!block.isEmpty())
				dataFS.put(Integer.toString(i), block);
		}
		fs.put("DataBlocks", dataFS);
		SimpleFieldSet checkFS = new SimpleFieldSet(false);
		checkFS.put("Count", checkBlocks.length);
		for (int i = 0; i < checkBlocks.length; i++) {
			SimpleFieldSet block = new SimpleFieldSet(false);
			if (checkURIs[i] != null)
				block.putSingle("URI", checkURIs[i].getURI().toString());
			SingleBlockInserter sbi = checkBlockInserters[i];
			// If encoded, then sbi == null => block finished
			boolean finished = encoded && sbi == null && checkURIs[i] != null;
			if (encoded) {
				block.put("Finished", finished);
			}
			if (!finished) {
				Bucket data = checkBlocks[i];
				if (data != null
						&& data instanceof SerializableToFieldSetBucket) {
					SimpleFieldSet tmp = ((SerializableToFieldSetBucket) data)
							.toFieldSet();
					if (tmp == null)
						Logger.error(this, "Could not serialize " + data
								+ " - check block " + i + " of " + segNo);
					else
						block.put("Data", tmp);
				} else if (encoded) {
					Logger.error(this,
							"Could not save to disk (null or not serializable to fieldset) encoded="+encoded+" finished="+finished + " checkURI[i]="+checkURIs[i]+" : "
									+ data, new Exception());
					return null;
				}
			}
			if (!block.isEmpty())
				checkFS.put(Integer.toString(i), block);
		}
		fs.put("CheckBlocks", checkFS);
		return fs;
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
		}
		if (logMINOR)
			Logger.minor(this, "Starting segment " + segNo + " of " + parent
					+ " (" + parent.dataLength + "): " + this + " ( finished="
					+ finished + " encoded=" + encoded + " hasURIs=" + hasURIs
					+ ')');
		boolean fin = true;

		for (int i = 0; i < dataBlockInserters.length; i++) {
			if (dataBlocks[i] != null) { // else already finished on creation
				dataBlockInserters[i] = new SingleBlockInserter(parent.parent,
						dataBlocks[i], (short) -1, FreenetURI.EMPTY_CHK_URI,
						blockInsertContext, this, false, CHKBlock.DATA_LENGTH,
						i, getCHKOnly, false, false, parent.token, container, context, persistent);
				dataBlockInserters[i].schedule(container, context);
				fin = false;
			} else {
				parent.parent.completedBlock(true, container, context);
			}
		}
		// parent.parent.notifyClients();
		started = true;
		if (!encoded) {
			if (logMINOR)
				Logger.minor(this, "Segment " + segNo + " of " + parent + " ("
						+ parent.dataLength + ") is not encoded");
			if (splitfileAlgo != null) {
				if (logMINOR)
					Logger.minor(this, "Encoding segment " + segNo + " of "
							+ parent + " (" + parent.dataLength + ')');
				// Encode blocks
				synchronized(this) {
					if(!encoded){
						splitfileAlgo.addToQueue(new FECJob(splitfileAlgo, context.fecQueue, dataBlocks, checkBlocks, CHKBlock.DATA_LENGTH, blockInsertContext.persistentBucketFactory, this, false, parent.parent.getPriorityClass(), persistent), context.fecQueue, container);
					}
				}				
				fin = false;
			}
		} else {
			for (int i = 0; i < checkBlockInserters.length; i++) {
				if (checkBlocks[i] != null) {
					checkBlockInserters[i] = new SingleBlockInserter(
							parent.parent, checkBlocks[i], (short) -1,
							FreenetURI.EMPTY_CHK_URI, blockInsertContext, this,
							false, CHKBlock.DATA_LENGTH, i + dataBlocks.length,
							getCHKOnly, false, false, parent.token, container, context, persistent);
					checkBlockInserters[i].schedule(container, context);
					fin = false;
				} else
					parent.parent.completedBlock(true, container, context);
			}
			onEncodedSegment(container, context, null, dataBlocks, checkBlocks, null, null);
		}
		if (hasURIs) {
			parent.segmentHasURIs(this, container, context);
		}
		boolean fetchable;
		synchronized (this) {
			fetchable = (blocksCompleted > dataBlocks.length);
		}
		if(persistent)
			container.set(this);
		if (fetchable)
			parent.segmentFetchable(this, container);
		if (fin)
			finish(container, context, parent);
		if (finished) {
			parent.segmentFinished(this, container, context);
		}
		if(persistent)
			container.deactivate(parent, 1);
	}

	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {} // irrevelant

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		// Start the inserts
		try {
			if(logMINOR)
				Logger.minor(this, "Scheduling "+checkBlockInserters.length+" check blocks...");
			for (int i = 0; i < checkBlockInserters.length; i++) {
				// See comments on FECCallback: WE MUST COPY THE DATA BACK!!!
				checkBlocks[i] = checkBuckets[i];
				if(checkBlocks[i] == null) {
					if(logMINOR)
						Logger.minor(this, "Skipping check block "+i+" - is null");
					continue;
				}
				if(checkBlockInserters[i] != null) continue;
				checkBlockInserters[i] = new SingleBlockInserter(parent.parent,
						checkBlocks[i], (short) -1, FreenetURI.EMPTY_CHK_URI,
						blockInsertContext, this, false, CHKBlock.DATA_LENGTH,
						i + dataBlocks.length, getCHKOnly, false, false,
						parent.token, container, context, persistent);
				checkBlockInserters[i].schedule(container, context);
			}
		} catch (Throwable t) {
			Logger.error(this, "Caught " + t + " while encoding " + this, t);
			InsertException ex = new InsertException(
					InsertException.INTERNAL_ERROR, t, null);
			finish(ex, container, context, parent);
			if(persistent)
				container.deactivate(parent, 1);
			return;
		}

		synchronized (this) {
			encoded = true;
		}
		
		if(persistent)
			container.set(this);

		// Tell parent only after have started the inserts.
		// Because of the counting.
		parent.encodedSegment(this, container, context);

		synchronized (this) {
			for (int i = 0; i < dataBlockInserters.length; i++) {
				if (dataBlockInserters[i] == null && dataBlocks[i] != null) {
					dataBlocks[i].free();
					dataBlocks[i] = null;
				}
			}
		}
		
		if(persistent) {
			container.set(this);
			container.deactivate(parent, 1);
		}
	}

	/**
	 * Caller must activate and pass in parent.
	 * @param ex
	 * @param container
	 * @param context
	 * @param parent
	 */
	private void finish(InsertException ex, ObjectContainer container, ClientContext context, SplitFileInserter parent) {
		if (logMINOR)
			Logger.minor(this, "Finishing " + this + " with " + ex, ex);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			toThrow = ex;
		}
		if(persistent) {
			container.set(this);
		}
		parent.segmentFinished(this, container, context);
	}

	/**
	 * Caller must activate and pass in parent.
	 * @param container
	 * @param context
	 * @param parent
	 */
	private void finish(ObjectContainer container, ClientContext context, SplitFileInserter parent) {
		if(persistent)
			container.activate(errors, 5);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			toThrow = InsertException.construct(errors);
		}
		if(persistent) {
			container.set(this);
		}
		parent.segmentFinished(this, container, context);
	}

	public void onEncode(BaseClientKey k, ClientPutState state, ObjectContainer container, ClientContext context) {
		ClientCHK key = (ClientCHK) k;
		SingleBlockInserter sbi = (SingleBlockInserter) state;
		int x = sbi.token;
		synchronized (this) {
			if (finished)
				return;
			if (x >= dataBlocks.length) {
				if (checkURIs[x - dataBlocks.length] != null) {
					return;
				}
				checkURIs[x - dataBlocks.length] = key;
			} else {
				if (dataURIs[x] != null) {
					return;
				}
				dataURIs[x] = key;
			}
			blocksGotURI++;
			if(persistent)
				container.set(this);
			if (blocksGotURI != dataBlocks.length + checkBlocks.length)
				return;
			// Double check
			for (int i = 0; i < checkURIs.length; i++) {
				if (checkURIs[i] == null) {
					Logger.error(this, "Check URI " + i + " is null");
					return;
				}
			}
			for (int i = 0; i < dataURIs.length; i++) {
				if (dataURIs[i] == null) {
					Logger.error(this, "Data URI " + i + " is null");
					return;
				}
			}
			hasURIs = true;
		}
		if(persistent) {
			container.activate(parent, 1);
			container.set(this);
		}
		parent.segmentHasURIs(this, container, context);
		if(persistent)
			container.deactivate(parent, 1);
	}

	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
		}
		if (parent.parent.isCancelled()) {
			parent.cancel(container, context);
			return;
		}
		SingleBlockInserter sbi = (SingleBlockInserter) state;
		int x = sbi.token;
		completed(x, container, context);
		if(persistent) {
			container.deactivate(parent.parent, 1);
			container.deactivate(parent, 1);
		}
	}

	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(parent.parent, 1);
			container.activate(errors, 1);
		}
		if (parent.parent.isCancelled()) {
			parent.cancel(container, context);
			return;
		}
		SingleBlockInserter sbi = (SingleBlockInserter) state;
		int x = sbi.token;
		errors.merge(e);
		completed(x, container, context);
		if(persistent) {
			container.deactivate(parent.parent, 1);
			container.deactivate(parent, 1);
			container.deactivate(errors, 1);
		}
	}

	private void completed(int x, ObjectContainer container, ClientContext context) {
		int total = innerCompleted(x, container);
		if (total == -1)
			return;
		if (total == dataBlockInserters.length) {
			if(persistent)
				container.activate(parent, 1);
			parent.segmentFetchable(this, container);
		}
		if (total != dataBlockInserters.length + checkBlockInserters.length)
			return;
		if(persistent)
			container.set(this);
		finish(container, context, parent);
	}

	/**
	 * Called when a block has completed.
	 * 
	 * @param x
	 *            The block number.
	 * @return -1 if the segment has already finished, otherwise the number of
	 *         completed blocks.
	 */
	private synchronized int innerCompleted(int x, ObjectContainer container) {
		if (logMINOR)
			Logger.minor(this, "Completed: " + x + " on " + this
					+ " ( completed=" + blocksCompleted + ", total="
					+ (dataBlockInserters.length + checkBlockInserters.length));

		if (finished)
			return -1;
		if (x >= dataBlocks.length) {
			x -= dataBlocks.length;
			if (checkBlockInserters[x] == null) {
				Logger.error(this, "Completed twice: check block " + x + " on "
						+ this, new Exception());
				return blocksCompleted;
			}
			checkBlockInserters[x] = null;
			if(persistent)
				container.activate(checkBlocks[x], 1);
			checkBlocks[x].free();
			checkBlocks[x] = null;
		} else {
			if (dataBlockInserters[x] == null) {
				Logger.error(this, "Completed twice: data block " + x + " on "
						+ this, new Exception());
				return blocksCompleted;
			}
			dataBlockInserters[x] = null;
			if (encoded) {
				if(persistent)
					container.activate(dataBlocks[x], 1);
				dataBlocks[x].free();
				dataBlocks[x] = null;
			}
		}
		blocksCompleted++;
		if(persistent)
			container.set(this);
		return blocksCompleted;
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	public boolean isEncoded() {
		return encoded;
	}

	public int countCheckBlocks() {
		return checkBlocks.length;
	}

	public int countDataBlocks() {
		return dataBlocks.length;
	}

	public ClientCHK[] getCheckCHKs() {
		return checkURIs;
	}

	public ClientCHK[] getDataCHKs() {
		return dataURIs;
	}

	InsertException getException() {
		synchronized (this) {
			return toThrow;
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			if (toThrow != null)
				toThrow = new InsertException(InsertException.CANCELLED);
		}
		cancelInner(container, context);
	}

	private void cancelInner(ObjectContainer container, ClientContext context) {
		for (int i = 0; i < dataBlockInserters.length; i++) {
			SingleBlockInserter sbi = dataBlockInserters[i];
			if (sbi != null)
				sbi.cancel(container, context);
			Bucket d = dataBlocks[i];
			if (d != null) {
				if(persistent)
					container.activate(d, 5);
				d.free();
				d.removeFrom(container);
				dataBlocks[i] = null;
			}
		}
		for (int i = 0; i < checkBlockInserters.length; i++) {
			SingleBlockInserter sbi = checkBlockInserters[i];
			if (sbi != null)
				sbi.cancel(container, context);
			Bucket d = checkBlocks[i];
			if (d != null) {
				if(persistent)
					container.activate(d, 5);
				d.free();
				d.removeFrom(container);
				checkBlocks[i] = null;
			}
		}
		if(persistent) {
			container.set(this);
			container.activate(parent, 1);
		}
		parent.segmentFinished(this, container, context);
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		Logger.error(this, "Illegal transition in SplitFileInserterSegment: "
				+ oldState + " -> " + newState);
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Got onMetadata from " + state);
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
		Logger.error(this, "Should not happen: onBlockSetFinished(" + state
				+ ") on " + this);
	}

	public synchronized boolean hasURIs() {
		return hasURIs;
	}

	public synchronized boolean isFetchable() {
		return blocksCompleted >= dataBlocks.length;
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		// Ignore
	}

	/**
	 * Force the remaining blocks which haven't been encoded so far to be
	 * encoded ASAP.
	 */
	public void forceEncode(ObjectContainer container, ClientContext context) {
		context.backgroundBlockEncoder.queue(dataBlockInserters, container, context);
		context.backgroundBlockEncoder.queue(checkBlockInserters, container, context);
	}

	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) {
				Logger.error(this, "FEC decode or encode failed but already finished: "+t, t);
				return;
			}
			finished = true;
			Logger.error(this, "Insert segment failed: "+t+" for "+this, t);
			this.toThrow = new InsertException(InsertException.INTERNAL_ERROR, "FEC failure: "+t, null);
		}
		cancelInner(container, context);
	}
}
