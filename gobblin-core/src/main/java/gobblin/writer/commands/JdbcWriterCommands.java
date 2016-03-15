package gobblin.writer.commands;

import gobblin.converter.jdbc.JdbcEntryData;

import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.Map;

public interface JdbcWriterCommands {

  public void createTableStructure(Connection conn, String fromStructure, String targetTableName) throws SQLException;

  public boolean isEmpty(Connection conn, String table) throws SQLException;

  public void truncate(Connection conn, String table) throws SQLException;

  public void deleteAll(Connection conn, String table) throws SQLException;

  public void drop(Connection conn, String table) throws SQLException;

  public Map<String, JDBCType> retrieveDataColumns(Connection conn, String table) throws SQLException;

  public void insert(Connection conn, String table, JdbcEntryData jdbcEntryData) throws SQLException;

  public void flush(Connection conn) throws SQLException;

  public void copyTable(Connection conn, String from, String to) throws SQLException;
}