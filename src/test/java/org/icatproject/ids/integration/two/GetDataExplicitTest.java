package org.icatproject.ids.integration.two;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.InputStream;

import org.icatproject.ids.integration.BaseTest;
import org.icatproject.ids.integration.util.Setup;
import org.icatproject.ids.integration.util.client.BadRequestException;
import org.icatproject.ids.integration.util.client.DataNotOnlineException;
import org.icatproject.ids.integration.util.client.DataSelection;
import org.icatproject.ids.integration.util.client.IdsException;
import org.icatproject.ids.integration.util.client.InsufficientPrivilegesException;
import org.icatproject.ids.integration.util.client.TestingClient.Flag;
import org.junit.BeforeClass;
import org.junit.Test;

public class GetDataExplicitTest extends BaseTest {

	// value of the offset in bytes
	final Integer goodOffset = 20;
	final Integer badOffset = 99999999;

	@BeforeClass
	public static void setup() throws Exception {
		setup = new Setup("two.properties");
		icatsetup();
	}

	@Test(expected = BadRequestException.class)
	public void badPreparedIdFormatTest() throws Exception {
		try (InputStream z = testingClient.getData("bad preparedId format", "filename", 0L, 400)) {
		}
	}

	@Test(expected = InsufficientPrivilegesException.class)
	public void forbiddenTest() throws Exception {
		try (InputStream z = testingClient.getData(setup.getForbiddenSessionId(),
				new DataSelection().addDatafiles(datafileIds), Flag.NONE, null, 0, 403)) {
		}
	}

	@Test
	public void correctBehaviourTest() throws Exception {

		try (InputStream z = testingClient.getData(sessionId,
				new DataSelection().addDatafiles(datafileIds), Flag.NONE, null, 0, 404)) {

			fail("Should have thrown exception");
		} catch (IdsException e) {
			assertEquals(DataNotOnlineException.class, e.getClass());
		}

		while (true) {
			try (InputStream stream = testingClient.getData(sessionId,
					new DataSelection().addDatafiles(datafileIds), Flag.NONE, null, 0, null)) {
				checkZipStream(stream, datafileIds, 57);
				break;
			} catch (IdsException e) {
				assertEquals(DataNotOnlineException.class, e.getClass());
				Thread.sleep(1000);
			}
		}
	}

	@Test
	public void gettingDatafileRestoresItsDatasetTest() throws Exception {

		try (InputStream z = testingClient.getData(sessionId,
				new DataSelection().addDatafile(datafileIds.get(2)), Flag.NONE, null, 0, null)) {
			fail("Should have thrown an exception");
		} catch (DataNotOnlineException e) {
			// All is well
		}

		waitForIds();
		try (InputStream stream = testingClient.getData(sessionId,
				new DataSelection().addDatafile(datafileIds.get(3)), Flag.NONE, null, 0, 200)) {
			checkStream(stream, datafileIds.get(3));
		}

	}

	@Test
	public void gettingDatasetUsesCacheTest() throws Exception {

		try (InputStream z = testingClient.getData(sessionId,
				new DataSelection().addDataset(datasetIds.get(0)), Flag.NONE, null, 0, null)) {
			fail("Should have thrown an exception");
		} catch (DataNotOnlineException e) {
			// All is well
		}

		waitForIds();

		try (InputStream stream = testingClient.getData(sessionId,
				new DataSelection().addDataset(datasetIds.get(0)), Flag.NONE, null, 0, 200)) {
			checkZipStream(stream, datafileIds.subList(0, 2), 57);
		}

	}

	@Test
	public void gettingDatafileAndDatasetShouldRestoreBothDatasetsTest() throws Exception {

		try (InputStream z = testingClient.getData(sessionId,
				new DataSelection().addDatafile(datafileIds.get(2)).addDataset(datasetIds.get(0)),
				Flag.NONE, null, 0, 404)) {
			fail("Should throw exception");
		} catch (DataNotOnlineException e) {
			// All is well
		}

		waitForIds();
		try (InputStream stream = testingClient.getData(sessionId,
				new DataSelection().addDatasets(datasetIds), Flag.NONE, null, 0, 200)) {
			checkZipStream(stream, datafileIds, 57);
		}
	}

}
