package com.sensepost.mallet.swing;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.ReferenceCountUtil;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.Date;
import java.util.prefs.Preferences;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.sensepost.mallet.model.ChannelEvent;
import com.sensepost.mallet.model.ChannelEvent.ChannelEventType;
import com.sensepost.mallet.model.ChannelEvent.ChannelMessageEvent;
import com.sensepost.mallet.model.ChannelEvent.ExceptionCaughtEvent;
import com.sensepost.mallet.model.ChannelEvent.UserEventTriggeredEvent;
import com.sensepost.mallet.swing.editors.EditorController;
import com.sensepost.mallet.swing.editors.ObjectEditor;

public class ConnectionDataPanel extends JPanel {

	private final ListModel<ChannelEvent> EMPTY = new DefaultListModel<ChannelEvent>();
	private ListTableModelAdapter tableModel = new ListTableModelAdapter();

	private JButton dropButton, sendButton;

	private ConnectionData connectionData = null;
	private EditorController editorController = new EditorController();
	private ChannelEventRenderer channelEventRenderer = new ChannelEventRenderer();
	private DateRenderer dateRenderer = new DateRenderer(true);
	private DirectionRenderer directionRenderer = new DirectionRenderer();

	private ChannelMessageEvent editing = null;
	private JTable table;
	private Preferences prefs = Preferences
			.userNodeForPackage(ConnectionDataPanel.class).node(ConnectionDataPanel.class.getSimpleName());

	private enum Direction  { Client_Server, Server_Client }

	public ConnectionDataPanel() {
		setLayout(new BorderLayout(0, 0));

		JSplitPane splitPane = new JSplitPane();
		splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
		add(splitPane, BorderLayout.CENTER);

		JPanel pendingPanel = new JPanel();
		pendingPanel.setLayout(new BorderLayout(0, 0));
		splitPane.setBottomComponent(pendingPanel);
		SplitPanePersistence spp = new SplitPanePersistence(prefs);
		spp.apply(splitPane, 200);
		splitPane.addPropertyChangeListener(spp);

		ObjectEditor editor = new AutoEditor();
		editor.setEditorController(editorController);
		pendingPanel.add(editor.getEditorComponent(), BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel();
		pendingPanel.add(buttonPanel, BorderLayout.SOUTH);
		buttonPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));

		dropButton = new JButton("Drop");
		dropButton.addActionListener(new DropAction());
		dropButton.setEnabled(false);
		buttonPanel.add(dropButton);

		sendButton = new JButton("Send");
		sendButton.addActionListener(new SendAction());
		sendButton.setEnabled(false);
		buttonPanel.add(sendButton);

		JPanel topPanel = new JPanel(new BorderLayout());
		splitPane.setTopComponent(topPanel);
		JScrollPane scrollPane = new JScrollPane();
		topPanel.add(scrollPane, BorderLayout.CENTER);

		table = new JTable(tableModel);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		scrollPane.setViewportView(table);
		table.getSelectionModel().addListSelectionListener(
				new EventSelectionListener());
		table.setDefaultRenderer(Date.class, dateRenderer);
		table.setDefaultRenderer(ChannelEvent.class, channelEventRenderer);
		table.setDefaultRenderer(Direction.class, directionRenderer);
		table.setAutoCreateRowSorter(true);

		TableColumnModelPersistence tcmp = new TableColumnModelPersistence(
				prefs, "column_widths");
		tcmp.apply(table.getColumnModel(), 75, 75, 75, 200, 800);
		table.getColumnModel().addColumnModelListener(tcmp);
	}

	public void setConnectionData(ConnectionData connectionData) {
		if (this.connectionData == connectionData)
			return;

		this.connectionData = connectionData;

		if (connectionData != null) {
			tableModel.setListModel(connectionData.getEvents());
		} else {
			tableModel.setListModel(null);
		}
	}

	private class ChannelEventRenderer extends DefaultTableCellRenderer {

		@Override
		public Component getTableCellRendererComponent(JTable table,
				Object value, boolean isSelected, boolean hasFocus, int row,
				int column) {
			if (value instanceof ChannelEvent) {
				ChannelEvent evt = (ChannelEvent) value;
				value = "";
				if (evt instanceof ChannelMessageEvent) {
					Object o = ((ChannelMessageEvent) evt).getMessage();
					if (o != null) {
						value = o.getClass().getName();
						if (o instanceof ByteBuf)
							value += " (" + ((ByteBuf) o).readableBytes()
									+ " bytes)";
						else if (o instanceof byte[]) {
							value += " (" + ((byte[]) o).length + " bytes)";
						} else 
							value += " (" + o.toString() + ")";
					}
					ReferenceCountUtil.release(o);
				} else if (evt.type().equals(ChannelEventType.USER_EVENT_TRIGGERED)) {
					Object uevt = ((UserEventTriggeredEvent) evt)
							.userEvent();
					if (uevt != null) {
						if ((uevt instanceof ChannelInputShutdownEvent))
							value = "Input Shutdown";
						else
							value = "UserEvent " + uevt.toString();
					} else
						value += " UserEvent (null)";
				} else if (evt.type().equals(ChannelEventType.EXCEPTION_CAUGHT)) {
					String cause = ((ExceptionCaughtEvent) evt).cause();
					int cr = cause.indexOf('\n');
					if (cr != -1)
						cause = cause.substring(0, cr);
					value = cause;
				}
			}
			return super.getTableCellRendererComponent(table, value,
					isSelected, hasFocus, row, column);
		}

	}

	private class DirectionRenderer extends DefaultTableCellRenderer {

		@Override
		public Component getTableCellRendererComponent(JTable table,
				Object value, boolean isSelected, boolean hasFocus, int row,
				int column) {
			if (value instanceof Direction) {
				Direction d = (Direction) value;
				if (d == Direction.Client_Server)
					value = "Client to Proxy";
				else
					value = "Proxy to Server";
			}
			return super.getTableCellRendererComponent(table, value,
					isSelected, hasFocus, row, column);
		}

	}

	private class ListTableModelAdapter extends AbstractTableModel implements
			ListDataListener {

		private ListModel<ChannelEvent> listModel = null;
		private String[] columnNames = new String[] { "Received", "Sent",
				"Direction", "Event Type", "Value" };
		private Class<?>[] columnClasses = new Class<?>[] { Date.class,
				Date.class, Direction.class, String.class, ChannelEvent.class };

		public void setListModel(ListModel<ChannelEvent> listModel) {
			if (this.listModel != null)
				this.listModel.removeListDataListener(this);
			this.listModel = listModel;
			if (this.listModel != null)
				this.listModel.addListDataListener(this);
			fireTableDataChanged();
		}

		public ChannelEvent getElementAt(int rowIndex) {
			return listModel.getElementAt(rowIndex);
		}

		@Override
		public int getRowCount() {
			return listModel == null ? 0 : listModel.getSize();
		}

		@Override
		public int getColumnCount() {
			return columnNames.length;
		}

		@Override
		public String getColumnName(int columnIndex) {
			return columnNames[columnIndex];
		}

		@Override
		public Class<?> getColumnClass(int columnIndex) {
			return columnClasses[columnIndex];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			if (listModel == null || rowIndex > listModel.getSize())
				return null;
			ChannelEvent e = listModel.getElementAt(rowIndex);
			switch (columnIndex) {
			case 0:
				return e.eventTime();
			case 1:
				return e.isExecuted() ? e.executionTime() : null;
			case 2:
				return listModel.getElementAt(0).channelId().equals(e.channelId()) ? Direction.Client_Server : Direction.Server_Client;
			case 3:
				return e.type();
			case 4:
				return e;
			}
			return null;
		}

		@Override
		public void intervalAdded(ListDataEvent e) {
			fireTableRowsInserted(e.getIndex0(), e.getIndex1());
		}

		@Override
		public void intervalRemoved(ListDataEvent e) {
			fireTableRowsDeleted(e.getIndex0(), e.getIndex1());
		}

		@Override
		public void contentsChanged(ListDataEvent e) {
			fireTableRowsUpdated(e.getIndex0(), e.getIndex1());
		}

	}

	private class DropAction implements ActionListener {

		public void actionPerformed(ActionEvent e) {
			int n = table.getSelectedRow();
			if (n >= 0) {
				try {
					connectionData.dropNextEvents(n);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			} else {
				try {
					connectionData.dropNextEvent();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
			updateButtonState(null);
			if (n < table.getRowCount() - 1)
				table.getSelectionModel().setLeadSelectionIndex(n+1);
		}
	}

	private class SendAction implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			int n = table.getSelectedRow();
			if (n >= 0) {
				if (editing != null && !editorController.isReadOnly()) {
					Object o = editorController.getObject();
					ReferenceCountUtil.retain(o);
					editing.setMessage(o);
					editing = null;
					editorController.setObject(null);
				}
				try {
					connectionData.executeNextEvents(n);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			} else {
				try {
					connectionData.executeNextEvent();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
			updateButtonState(null);
			if (n < table.getRowCount() - 1)
				table.getSelectionModel().setLeadSelectionIndex(n+1);
		}
	}

	private class EventSelectionListener implements ListSelectionListener {
		@Override
		public void valueChanged(ListSelectionEvent e) {
			if (e.getValueIsAdjusting())
				return;
			if (editing != null && !editorController.isReadOnly()) {
				Object o = editorController.getObject();
				ReferenceCountUtil.retain(o);
				editing.setMessage(o);
			}
			int selectedRow = table.getSelectedRow();
			if (selectedRow != -1)
				selectedRow = table.convertRowIndexToModel(selectedRow);
			ChannelEvent evt;
			if (selectedRow < 0)
				evt = null;
			else
				evt = connectionData.getEvents().getElementAt(selectedRow);
			if (evt instanceof ChannelMessageEvent) {
				editing = (ChannelMessageEvent) evt;
				Object o = editing.getMessage();
				editorController.setObject(o);
				ReferenceCountUtil.release(o);
				editorController.setReadOnly(evt.isExecuted());
			} else if (evt instanceof ExceptionCaughtEvent) {
				editing = null;
				editorController.setObject(((ExceptionCaughtEvent) evt)
						.cause());
				editorController.setReadOnly(true);
			} else if (evt instanceof UserEventTriggeredEvent) {
				editing = null;
				editorController.setObject(((UserEventTriggeredEvent) evt)
						.userEvent());
				editorController.setReadOnly(true);
			} else {
				editing = null;
				editorController.setObject(null);
				editorController.setReadOnly(true);
			}
			updateButtonState(evt);
		}
	}

	private void updateButtonState(ChannelEvent evt) {
		dropButton.setEnabled(evt != null && !evt.isExecuted());
		sendButton.setEnabled(evt != null && !evt.isExecuted());
	}
}
