package org.icatproject.ids.integration.two;

import java.nio.file.Path;

import org.icatproject.ids.integration.BaseTest;
import org.icatproject.ids.integration.util.Setup;
import org.icatproject.ids.integration.util.client.DataNotOnlineException;
import org.icatproject.ids.integration.util.client.DataSelection;
import org.junit.BeforeClass;
import org.junit.Test;

public class DeleteTest extends BaseTest {

	@BeforeClass
	public static void setup() throws Exception {
		setup = new Setup("two.properties");
		icatsetup();
	}

	@Test(expected = DataNotOnlineException.class)
	public void deleteFromUnrestoredDataset() throws Exception {
		testingClient.delete(sessionId, new DataSelection().addDataset(datasetIds.get(0)), 404);
	}

	@Test
	public void deleteDatafileFromRestoredDatasetTest() throws Exception {

		Path dirOnFastStorage = getDirOnFastStorage(datasetIds.get(1));
		Path datasetCacheFile = getDatasetCacheFile(datasetIds.get(1));
		Path fileOnArchiveStorage = getFileOnArchiveStorage(datasetIds.get(1));

		testingClient.restore(sessionId, new DataSelection().addDataset(datasetIds.get(1)), 200);
		waitForIds();
		checkPresent(dirOnFastStorage);
		checkPresent(datasetCacheFile);

		testingClient.delete(sessionId, new DataSelection().addDatafile(datafileIds.get(3)), 200);
		waitForIds();

		checkZipFile(fileOnArchiveStorage, datafileIds.subList(2, 3), 57);
	}

	@Test
	public void deleteRestoredDatasetTest() throws Exception {
		Path dirOnFastStorage = getDirOnFastStorage(datasetIds.get(1));
		Path datasetCacheFile = getDatasetCacheFile(datasetIds.get(1));
		Path fileOnArchiveStorage = getFileOnArchiveStorage(datasetIds.get(1));

		testingClient.restore(sessionId, new DataSelection().addDataset(datasetIds.get(1)), 200);
		waitForIds();
		checkPresent(dirOnFastStorage);
		checkPresent(datasetCacheFile);
		checkPresent(fileOnArchiveStorage);

		testingClient.delete(sessionId, new DataSelection().addDataset(datasetIds.get(1)), 200);
		waitForIds();
		checkAbsent(dirOnFastStorage);
		checkAbsent(datasetCacheFile);
		checkAbsent(fileOnArchiveStorage);
	}

}
