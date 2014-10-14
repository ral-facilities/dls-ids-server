package org.icatproject.ids;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.json.Json;
import javax.json.stream.JsonGenerator;

import org.icatproject.ids.exceptions.InternalException;
import org.icatproject.ids.plugin.DsInfo;
import org.icatproject.ids.thread.Archiver;
import org.icatproject.ids.thread.Restorer;
import org.icatproject.ids.thread.WriteThenArchiver;
import org.icatproject.ids.thread.Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class FiniteStateMachine {

	private PropertyHandler propertyHandler;

	private long processQueueIntervalMillis;

	private Path markerDir;

	@EJB
	IcatReader reader;

	@PostConstruct
	private void init() {
		try {
			propertyHandler = PropertyHandler.getInstance();
			archiveWriteDelayMillis = propertyHandler.getWriteDelaySeconds() * 1000L;
			processQueueIntervalMillis = propertyHandler.getProcessQueueIntervalSeconds() * 1000L;
			timer.schedule(new ProcessQueue(), processQueueIntervalMillis);
			markerDir = propertyHandler.getCacheDir().resolve("marker");
			Files.createDirectories(markerDir);
		} catch (IOException e) {
			throw new RuntimeException("FiniteStateMachine reports " + e.getClass() + " "
					+ e.getMessage());
		}
	}

	private long archiveWriteDelayMillis;

	private static Logger logger = LoggerFactory.getLogger(FiniteStateMachine.class);

	private Set<DsInfo> changing = new HashSet<>();
	private Map<DsInfo, Long> writeTimes = new HashMap<>();

	private Map<DsInfo, RequestedState> deferredOpsQueue = new HashMap<>();

	private Map<String, Set<Long>> locks = new HashMap<>();

	public void queue(DsInfo dsInfo, DeferredOp deferredOp) throws InternalException {
		logger.info("Requesting " + deferredOp + " of " + dsInfo);

		synchronized (deferredOpsQueue) {

			final RequestedState state = this.deferredOpsQueue.get(dsInfo);
			if (state == null) {
				if (deferredOp == DeferredOp.WRITE) {
					requestWrite(dsInfo);
				} else if (deferredOp == DeferredOp.ARCHIVE) {
					deferredOpsQueue.put(dsInfo, RequestedState.ARCHIVE_REQUESTED);
				} else if (deferredOp == DeferredOp.RESTORE) {
					deferredOpsQueue.put(dsInfo, RequestedState.RESTORE_REQUESTED);
				}
			} else if (state == RequestedState.ARCHIVE_REQUESTED) {
				if (deferredOp == DeferredOp.WRITE) {
					requestWrite(dsInfo);
					deferredOpsQueue.put(dsInfo, RequestedState.WRITE_THEN_ARCHIVE_REQUESTED);
				} else if (deferredOp == DeferredOp.RESTORE) {
					deferredOpsQueue.put(dsInfo, RequestedState.RESTORE_REQUESTED);
				}
			} else if (state == RequestedState.RESTORE_REQUESTED) {
				if (deferredOp == DeferredOp.WRITE) {
					requestWrite(dsInfo);
				} else if (deferredOp == DeferredOp.ARCHIVE) {
					deferredOpsQueue.put(dsInfo, RequestedState.ARCHIVE_REQUESTED);
				}
			} else if (state == RequestedState.WRITE_REQUESTED) {
				if (deferredOp == DeferredOp.WRITE) {
					setDelay(dsInfo);
				} else if (deferredOp == DeferredOp.ARCHIVE) {
					deferredOpsQueue.put(dsInfo, RequestedState.WRITE_THEN_ARCHIVE_REQUESTED);
				}
			} else if (state == RequestedState.WRITE_THEN_ARCHIVE_REQUESTED) {
				if (deferredOp == DeferredOp.WRITE) {
					setDelay(dsInfo);
				} else if (deferredOp == DeferredOp.RESTORE) {
					deferredOpsQueue.put(dsInfo, RequestedState.WRITE_REQUESTED);
				}
			}
		}

	}

	private void requestWrite(DsInfo dsInfo) throws InternalException {
		try {
			Path marker = markerDir.resolve(Long.toString(dsInfo.getDsId()));
			Files.createFile(marker);
			logger.debug("Created marker " + marker);
		} catch (FileAlreadyExistsException e) {
			// Pass will ignore this
		} catch (IOException e) {
			throw new InternalException(e.getClass() + " " + e.getMessage());
		}
		deferredOpsQueue.put(dsInfo, RequestedState.WRITE_REQUESTED);
		setDelay(dsInfo);
	}

	private void setDelay(DsInfo dsInfo) {
		writeTimes.put(dsInfo, System.currentTimeMillis() + archiveWriteDelayMillis);
		if (logger.isDebugEnabled()) {
			final Date d = new Date(writeTimes.get(dsInfo));
			logger.debug("Requesting delay of writing of " + dsInfo + " till " + d);
		}
	}

	private class ProcessQueue extends TimerTask {

		@Override
		public void run() {
			try {
				synchronized (deferredOpsQueue) {
					final long now = System.currentTimeMillis();
					final Iterator<Entry<DsInfo, RequestedState>> it = deferredOpsQueue.entrySet()
							.iterator();
					while (it.hasNext()) {
						final Entry<DsInfo, RequestedState> opEntry = it.next();
						final DsInfo dsInfo = opEntry.getKey();
						if (!changing.contains(dsInfo)) {
							final RequestedState state = opEntry.getValue();
							if (state == RequestedState.WRITE_REQUESTED) {
								if (now > writeTimes.get(dsInfo)) {
									logger.debug("Will process " + dsInfo + " with " + state);
									writeTimes.remove(dsInfo);
									changing.add(dsInfo);
									it.remove();
									final Thread w = new Thread(new Writer(dsInfo, propertyHandler,
											FiniteStateMachine.this, reader));
									w.start();
								}
							} else if (state == RequestedState.WRITE_THEN_ARCHIVE_REQUESTED) {
								if (now > writeTimes.get(dsInfo)) {
									logger.debug("Will process " + dsInfo + " with " + state);
									writeTimes.remove(dsInfo);
									changing.add(dsInfo);
									it.remove();
									final Thread w = new Thread(new WriteThenArchiver(dsInfo,
											propertyHandler, FiniteStateMachine.this, reader));
									w.start();
								}
							} else if (state == RequestedState.ARCHIVE_REQUESTED) {
								it.remove();
								long dsId = dsInfo.getDsId();
								if (isLocked(dsId)) {
									logger.debug("Archive of " + dsInfo
											+ " skipped because getData in progress");
									continue;
								}
								logger.debug("Will process " + dsInfo + " with " + state);
								changing.add(dsInfo);
								final Thread w = new Thread(new Archiver(dsInfo, propertyHandler,
										FiniteStateMachine.this));
								w.start();
							} else if (state == RequestedState.RESTORE_REQUESTED) {
								logger.debug("Will process " + dsInfo + " with " + state);
								changing.add(dsInfo);
								it.remove();
								final Thread w = new Thread(new Restorer(dsInfo, propertyHandler,
										FiniteStateMachine.this, reader));
								w.start();
							}
						}
					}
				}

			} finally {
				timer.schedule(new ProcessQueue(), processQueueIntervalMillis);
			}

		}

	}

	public boolean isLocked(long dsId) {
		synchronized (deferredOpsQueue) {
			for (Set<Long> lock : locks.values()) {
				if (lock.contains(dsId)) {
					return true;
				}
			}
			return false;
		}
	}

	public enum RequestedState {
		ARCHIVE_REQUESTED, RESTORE_REQUESTED, WRITE_REQUESTED, WRITE_THEN_ARCHIVE_REQUESTED
	}

	public void removeFromChanging(DsInfo dsInfo) {
		synchronized (deferredOpsQueue) {
			changing.remove(dsInfo);
		}

	}

	private Timer timer = new Timer();

	public String getServiceStatus() throws InternalException {
		Map<DsInfo, RequestedState> deferredOpsQueueClone;
		Set<DsInfo> changingClone;
		Collection<Set<Long>> locksContentsClone;
		synchronized (deferredOpsQueue) {
			deferredOpsQueueClone = new HashMap<>(deferredOpsQueue);
			changingClone = new HashSet<>(changing);
			locksContentsClone = new HashSet<>(locks.values());
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (JsonGenerator gen = Json.createGenerator(baos).writeStartObject()) {
			gen.writeStartArray("opsQueue");
			for (Entry<DsInfo, RequestedState> entry : deferredOpsQueueClone.entrySet()) {
				DsInfo item = entry.getKey();
				if (!changingClone.contains(item)) {
					gen.writeStartObject().write("dsInfo", item.toString())
							.write("request", entry.getValue().name()).writeEnd();
				}
			}
			for (DsInfo item : changingClone) {
				gen.writeStartObject().write("dsInfo", item.toString())
						.write("request", "CHANGING").writeEnd();
			}
			gen.writeEnd(); // end Array("opsQueue")

			gen.write("lockCount", locksContentsClone.size());

			Set<Long> lockedDs = new HashSet<>();

			for (Set<Long> entry : locksContentsClone) {
				lockedDs.addAll(entry);
			}
			gen.writeStartArray("lockedDs");
			for (Long dsId : lockedDs) {
				gen.write(dsId);
			}
			gen.writeEnd(); // end Array("lockedDs")

			gen.writeEnd(); // end Object()
		}
		return baos.toString();

	}

	/**
	 * Find any DsInfo which are changing or are queued for restoration
	 */
	public Set<DsInfo> getRestoring() {
		Map<DsInfo, RequestedState> deferredOpsQueueClone = new HashMap<>();
		Set<DsInfo> result = null;
		synchronized (deferredOpsQueue) {
			deferredOpsQueueClone.putAll(deferredOpsQueue);
			result = new HashSet<>(changing);
		}
		for (Entry<DsInfo, RequestedState> entry : deferredOpsQueueClone.entrySet()) {
			if (entry.getValue() == RequestedState.RESTORE_REQUESTED) {
				result.add(entry.getKey());
			}
		}
		return result;
	}

	public String lock(Set<Long> set) {
		String lockId = UUID.randomUUID().toString();
		synchronized (deferredOpsQueue) {
			locks.put(lockId, set);
		}
		return lockId;
	}

	public void unlock(String lockId) {
		synchronized (deferredOpsQueue) {
			locks.remove(lockId);
		}
	}

}
