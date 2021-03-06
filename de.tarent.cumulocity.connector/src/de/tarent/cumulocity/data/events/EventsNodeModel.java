package de.tarent.cumulocity.data.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.RowKey;
import org.knime.core.data.StringValue;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.StringCell;
import org.knime.core.data.time.zoneddatetime.ZonedDateTimeCellFactory;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.telekom.m2m.cot.restsdk.event.EventApi;
import com.telekom.m2m.cot.restsdk.event.EventCollection;
import com.telekom.m2m.cot.restsdk.util.CotSdkException;
import com.telekom.m2m.cot.restsdk.util.Filter.FilterBuilder;

import de.tarent.cumulocity.connector.CumulocityPortObject;
import de.tarent.cumulocity.data.IdIterator;
import de.tarent.cumulocity.data.MyJsonSerializer;
import de.tarent.cumulocity.data.RetrieveDataNodeModel;

/**
 * implementation of the node model of the "Events" node.
 * 
 * retrieves events from Cumulocity
 * 
 * @author tarent solutions GmbH
 */
public class EventsNodeModel extends RetrieveDataNodeModel {

	// private static final String ATTRIBUTE_NAME = "name";
	private static final NodeLogger logger = NodeLogger.getLogger(EventsNodeModel.class);
	private static final int IN_PORT_CONNECTION_SETTINGS = 0;

	/*
	 * we have 1 required and one optional input port (connection info + device
	 * selection) and one output port with the events
	 */
	protected EventsNodeModel() {
		super(new PortType[] { CumulocityPortObject.TYPE, BufferedDataTable.TYPE_OPTIONAL });
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected PortObjectSpec[] configure(final PortObjectSpec[] inSpecs) throws InvalidSettingsException {
		if (inSpecs == null || inSpecs.length == 0) {
			throw new InvalidSettingsException(
					"Cumulocity Connection Info required on port " + IN_PORT_CONNECTION_SETTINGS);
		}
		if (inSpecs.length == 2) {
			boolean hasStringColumn = false;
			final DataTableSpec dataTable = ((DataTableSpec) inSpecs[RetrieveDataNodeModel.IN_PORT_DATA_TABLE]);
			if (dataTable != null) {
				for (int i = 0; (i < dataTable.getNumColumns()) && !hasStringColumn; i++) {
					final DataColumnSpec columnSpec = dataTable.getColumnSpec(i);
					if (columnSpec.getType().isCompatible(StringValue.class)) {
						// found one string column
						hasStringColumn = true;
					}
				}
				if (!hasStringColumn) {
					throw new InvalidSettingsException("Input table must contain at least one String column");
				}
			}
		}
		return super.configure(inSpecs);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @throws CanceledExecutionException
	 */
	@Override
	protected PortObject[] execute(final PortObject[] inData, final ExecutionContext exec)
			throws CanceledExecutionException {
		final long maxNum = getMaxNumItemsToFetch();
		final EventApi eventApi = getEventApi((CumulocityPortObject) inData[0]);

		final IdIterator device_ids = retrieveDeviceIDs((BufferedDataTable) inData[IN_PORT_DATA_TABLE]);

		final DataTableSpec outputSpec = outputTableSpec();
		final BufferedDataContainer container = exec.createDataContainer(outputSpec);

		long rowIx = 0;
		while (rowIx < maxNum && device_ids.hasNext()) {
			final Optional<FilterBuilder> optionalFilter = addOptionalDateFilter(device_ids.next());
			final EventCollection ec;
			if (optionalFilter.isPresent()) {
				ec = eventApi.getEvents(optionalFilter.get());
			} else {
				ec = eventApi.getEvents();
			}
			ec.setPageSize(2000);
			// final Iterator<Event> eventsIterator = ec.stream().iterator();

			rowIx = retrieveEventsForFilter(exec, rowIx, maxNum, ec, container);
		}
		return new BufferedDataTable[] { container.getTable() };
	}

	protected long retrieveEventsForFilter(final ExecutionContext exec, long rowIx, final long maxNum,
			final EventCollection eventsCollection, final BufferedDataContainer container)
			throws CanceledExecutionException {

		final DataCell[] cells = new DataCell[7];
		boolean done = false;
		try {
			final Gson gson = MyJsonSerializer.getInstance();
			while (!done) {

				final JsonArray events = eventsCollection.getJsonArray();
				logger.info("Retrieved " + events.size() + " events as json elements");
				for (int i = 0; i < events.size() && rowIx < maxNum; i++) {
					IoTEvent event = gson.fromJson(events.get(i), IoTEvent.class);

					cells[0] = event.m_sourceId;
					cells[1] = event.m_eventType;
					cells[2] = event.m_creationTime;
					cells[3] = event.m_sourceName;
					cells[4] = event.m_sourceId;
					cells[5] = event.m_time;
					cells[6] = event.m_description;

					final RowKey key = RowKey.createRowKey(rowIx);
					final DataRow row = new DefaultRow(key, cells);
					container.addRowToTable(row);
					rowIx++;
				}
				if (rowIx >= maxNum) {
					logger.info("Retrieved maximal number (" + rowIx + ") of events to retrieve, will stop.");
					done = true;
				} else {
					exec.checkCanceled();
					if (rowIx % 1000 == 0)
						logger.info("... processed " + rowIx + " events");
					if (eventsCollection.hasNext()) {
						eventsCollection.next();
					} else {
						done = true;
					}
				}
			}
		} catch (CotSdkException cse) {
			if (rowIx == 0) {
				logger.error("Failed to retrieve any events!");
				throw cse;
			} else {
				logger.error("Retrieved only " + rowIx + " events, but there might be more!");
			}
			logger.error("Root cause: " + cse.getMessage());
		} finally {
			container.close();
			logger.info("Retrieved " + rowIx + " events.");
		}

		return rowIx;
	}

	/*- too slow
	protected PortObject[] _execute(final PortObject[] inData, final ExecutionContext exec)
			throws CanceledExecutionException {
		final long maxNum = getMaxNumItemsToFetch();
		final EventApi eventApi = getEventApi((CumulocityPortObject) inData[0]);

		final IdIterator device_ids = retrieveDeviceIDs((BufferedDataTable) inData[IN_PORT_DATA_TABLE]);

		final DataTableSpec outputSpec = outputTableSpec();
		final BufferedDataContainer container = exec.createDataContainer(outputSpec);

		long rowIx = 0;
		while (rowIx < maxNum && device_ids.hasNext()) {
			final Optional<FilterBuilder> optionalFilter = addOptionalDateFilter(device_ids.next());
			final Iterator<Event> eventsIterator;
			if (optionalFilter.isPresent()) {
				eventsIterator = eventApi.getEvents(optionalFilter.get()).stream().iterator();
			} else {
				eventsIterator = eventApi.getEvents().stream().iterator();
			}

			rowIx = retrieveEventsForFilter(exec, rowIx, maxNum, eventsIterator, container);
		}
		return new BufferedDataTable[] { container.getTable() };
	}

	protected long retrieveEventsForFilter(final ExecutionContext exec, long rowIx, final long maxNum,
			final Iterator<Event> eventsIterator, final BufferedDataContainer container)
			throws CanceledExecutionException {

		final DataCell[] cells = new DataCell[7];
		logger.error("Starting event iterator");
		long d1 = 0;
		long d2 = 0;
		long start1 = System.currentTimeMillis();
		try {
			while (eventsIterator.hasNext()) {

				long start = System.currentTimeMillis();
				final Event event = eventsIterator.next();
				final Map<String, Object> attributes = event.getAttributes();
				long t1 = System.currentTimeMillis();
				d1 += t1 - start;

				cells[0] = new StringCell(event.getId());
				cells[1] = new StringCell(event.getType());
				final Date creationtime = event.getCreationTime();
				cells[2] = ZonedDateTimeCellFactory.create(m_dateFormat.format(creationtime));
				final Object source = attributes.get("source");
				if (source != null && source instanceof ExtensibleObject) {
					final ExtensibleObject s = (ExtensibleObject) source;
					if (s.has(ATTRIBUTE_NAME)) {
						cells[3] = new StringCell(s.get(ATTRIBUTE_NAME).toString());
					} else {
						cells[3] = DataType.getMissingCell();
					}
					cells[4] = new StringCell(s.get("id").toString());
				}
				final Date time = (Date) attributes.get("time");
				if (time != null) {
					cells[5] = ZonedDateTimeCellFactory.create(m_dateFormat.format(time));
				} else {
					cells[5] = DataType.getMissingCell();
				}
				cells[6] = new StringCell(event.getText());

				final RowKey key = RowKey.createRowKey(rowIx);
				final DataRow row = new DefaultRow(key, cells);
				container.addRowToTable(row);

				rowIx++;
				if (rowIx >= maxNum) {
					logger.info("Retrieved maximal number (" + rowIx + ") of events to retrieve, will stop.");
					break;
				}
				exec.checkCanceled();
				d2 += System.currentTimeMillis() - t1;
				if (rowIx % 100 == 0)
					logger.error("processed first " + rowIx + " events");
			}
		} catch (CotSdkException cse) {
			if (rowIx == 0) {
				logger.error("Failed to retrieve any events!");
				throw cse;
			} else {
				logger.error("Retrieved only " + rowIx + " events, but there might be more!");
			}
			logger.error("Root cause: " + cse.getMessage());
		} finally {
			container.close();
			logger.info("Retrieved " + rowIx + " events.");
		}

		logger.error("Total time : " + (System.currentTimeMillis() - start1));
		logger.error("Total iterator time for " + rowIx + " events: " + d1);
		logger.error("Total processing time for " + rowIx + " events: " + d2);
		return rowIx;
	}
*/
	
	protected DataTableSpec outputTableSpec() {
		final List<DataColumnSpec> columns = new ArrayList<>();
		columns.add(new DataColumnSpecCreator("Event ID", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Event Type", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Creation Time", ZonedDateTimeCellFactory.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Source Name", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Source ID", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Time", ZonedDateTimeCellFactory.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Description", StringCell.TYPE).createSpec());
		final DataTableSpec outputSpec = new DataTableSpec(columns.toArray(new DataColumnSpec[0]));
		return outputSpec;
	}
}
