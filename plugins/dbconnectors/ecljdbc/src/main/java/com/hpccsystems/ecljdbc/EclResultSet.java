package com.hpccsystems.ecljdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.Properties;

/**
 *
 * @author rpastrana
 */
public class EclResultSet implements ResultSet
{
	private boolean					closed		= false;
	private List<List>				rows;
	private int						index		= -1;
	private EclResultSetMetadata	resultMetadata;
	private EclDatabaseMetaData		dbMetadata;
	private Statement				statement;
	private String					test_query	= "SELECT 1";
	private String 					defaultEclQueryReturnDatasetName;
	private Object					lastResult;
	private ArrayList<SQLWarning> 	warnings;

	private static final int NumberOfCommonParamInThisIndex = 0;
	private static final int LeftMostKeyIndexPosition = 1;
	private static final int NumberofColsKeyedInThisIndex = 2;
	private static final int INDEXSCORECRITERIA = 3;

	public EclResultSet(List recrows, ArrayList<EclColumnMetaData> metadatacols, String tablename) throws SQLException
	{
		resultMetadata = new EclResultSetMetadata(metadatacols, tablename);
		rows = new ArrayList<List>(recrows);
		lastResult = new Object();
		warnings = new ArrayList<SQLWarning>();
	}

	public EclResultSet(Statement statement, String query, Map inParameters) throws SQLException
	{
		warnings = new ArrayList<SQLWarning>();
		try
		{

			//for debug:
//			System.out.println("Query: " + query);
//			Iterator entries = inParameters.entrySet().iterator();
//
//			while (entries.hasNext())
//			{
//				Entry thisEntry = (Entry) entries.next();
//				Object key = thisEntry.getKey();
//				Object value = thisEntry.getValue();
//				System.out.println("EclResultSet:inparameter: "	+ key.toString() + ":" + value.toString());
//			}

			lastResult = new Object();
			List<EclColumnMetaData> expectedretcolumns = null;
			this.statement = statement;
			EclConnection connection = (EclConnection) statement.getConnection();
			dbMetadata = connection.getDatabaseMetaData();
			ArrayList dsList = null;
			rows = new ArrayList();
			String indextousename = null;

			SqlParser parser = new SqlParser();
			parser.parse(query);
			int sqlreqtype = parser.getSqlType();

			//not sure this is actually needed...
			parser.populateParametrizedExpressions(inParameters);

			if(sqlreqtype == SqlParser.SQL_TYPE_SELECT)
			{
				String hpccfilename = Utils.handleQuotedString(parser.getTableName());
				if(!dbMetadata.tableExists("", hpccfilename))
					throw new Exception("Invalid table found: " + hpccfilename);

				DFUFile dfufile = dbMetadata.getDFUFile(hpccfilename);
				parser.verifySelectColumns(dfufile);

				expectedretcolumns = parser.getSelectColumns();

				if(((EclPreparedStatement)statement).isIndexSet())
				{
					indextousename = ((EclPreparedStatement)statement).getIndexToUse();
				}

				else
				{
					String indexhint = parser.getIndexHint();
					boolean avoidindex = false;
					if ( indexhint != null)
					{
						if (indexhint.trim().equals("0"))
						{
							avoidindex= true;
							System.out.println("Will not use any index.");
						}

						if (!avoidindex)
						{
							indextousename = findAppropriateIndex(indexhint,  expectedretcolumns, parser);
							if (indextousename == null)
								System.out.println("Cannot use USE INDEX hint: " + indexhint);
						}
					}

					if (indextousename == null && dfufile.hasRelatedIndexes() && !avoidindex)
					{
						indextousename = findAppropriateIndex(dfufile.getRelatedIndexesList(),  expectedretcolumns, parser);
					}

					((EclPreparedStatement)statement).setIndexToUse(indextousename);
				}

				//columns are base 1 indexed
				resultMetadata = new EclResultSetMetadata(expectedretcolumns, hpccfilename);
			}
			else if(sqlreqtype == SqlParser.SQL_TYPE_SELECTCONST)
			{
				expectedretcolumns = parser.getSelectColumns();
				resultMetadata = new EclResultSetMetadata(expectedretcolumns, "Constants");
			}
			else if(sqlreqtype == SqlParser.SQL_TYPE_CALL)
			{
				ArrayList<EclColumnMetaData> storeProcInParams = new ArrayList();

				String hpccQuery = Utils.handleQuotedString(parser.getStoredProcName());
				if(!dbMetadata.eclQueryExists("", hpccQuery))
					throw new Exception("Invalid store procedure found");

				defaultEclQueryReturnDatasetName = dbMetadata.getdefaultECLQueryResultDatasetName("", hpccQuery);

				expectedretcolumns = dbMetadata.getStoredProcOutColumns("", hpccQuery);
				storeProcInParams = dbMetadata.getStoredProcInColumns("",hpccQuery);
				//columns are base 1 indexed
				resultMetadata = new EclResultSetMetadata(expectedretcolumns, hpccQuery);
			}
			else
			{
				throw new SQLException("SQL request type not determined");
			}

			EclEngine eclengine = new EclEngine(parser, dbMetadata, connection.getProperties(), indextousename);
			dsList = eclengine.execute();

			// Get the data
			fetchData(dsList,expectedretcolumns);
			return;
		}
		catch (Exception ex)
		{
			if (ex.getMessage() != null)
				warnings.add(new SQLWarning(ex.getMessage()));
			throw new SQLException(ex);
		}
	}

	private void fetchData(ArrayList dsList, List<EclColumnMetaData> expectedretcolumns)
	{
		// Get the data
		// Iterate the Datasets - need to find the appropriate result dataset
		for (int i = 0; i < dsList.size(); i++)
		{
			// Iterate the rows
			ArrayList inRowList = (ArrayList) dsList.get(i);
			for (int j = 0; j < inRowList.size(); j++)
			{
				//ArrayList rowValues = new ArrayList();
				//create row with default values b/c HPCC will not return a column for empty fields
				ArrayList rowValues = resultMetadata.createDefaultResultRow();
				rows.add(rowValues);

				// Iterate the columns and add values
				ArrayList inColumnList = (ArrayList) inRowList.get(j);
				for (int m = 0; m < expectedretcolumns.size(); m++)
				{
					for (int k = 0; k < inColumnList.size(); k++)
					{
						EclColumn inColumn = (EclColumn) inColumnList.get(k);
						EclColumnMetaData mthexpectedcol = expectedretcolumns.get(m);
						if (mthexpectedcol.getColumnName().equalsIgnoreCase(inColumn.getName()) ||
							mthexpectedcol.getAlias() != null && mthexpectedcol.getAlias().equalsIgnoreCase(inColumn.getName()))
							rowValues.set(m, inColumn.getValue());
					}
				}
			}
		}
	}

	public String findAppropriateIndex(String index, List<EclColumnMetaData> expectedretcolumns, /*HashMap parameters, */ SqlParser parser)
	{
		List<String> indexhint = new ArrayList<String>();
		indexhint.add(index);
		return findAppropriateIndex(indexhint, expectedretcolumns, parser);
	}

	public String findAppropriateIndex(List<String> relindexes, List<EclColumnMetaData> expectedretcolumns, /*HashMap parameters, */ SqlParser parser)
	{
		String indextouse = null;
		String [] sqlqueryparamnames = parser.getWhereClauseNames();

		int totalparamcount = parser.getWhereClauseExpressionsCount();

		int indexscore [][] = new int 	[relindexes.size()]
										[INDEXSCORECRITERIA/*[FieldsInIndexCount][LeftMostKeyIndex][ColsKeyedcount]*/];
		int highscore = Integer.MIN_VALUE;
		boolean payloadIdxWithAtLeast1KeyedFieldFound = false;

		for (int indexcounter = 0; indexcounter < relindexes.size(); indexcounter++)
		{
			String indexname = relindexes.get(indexcounter);
			DFUFile indexfile = dbMetadata.getDFUFile(indexname);

			if (indexfile!= null && indexfile.isKeyFile() && indexfile.hasValidIdxFilePosField())
			{
				for(int j = 0; j < expectedretcolumns.size(); j++)
				{
					if(indexfile.containsField(expectedretcolumns.get(j), true))
						++indexscore[indexcounter][NumberOfCommonParamInThisIndex];
				}

				if (payloadIdxWithAtLeast1KeyedFieldFound && indexscore[indexcounter][NumberOfCommonParamInThisIndex] == 0)
					break; //Don't bother with this index

				int localleftmostindex = Integer.MAX_VALUE;
				Properties KeyColumns = indexfile.getKeyedColumns();

				if(KeyColumns!=null)
				{
					for (int i = 0; i < sqlqueryparamnames.length; i++)
					{
						String currentparam = sqlqueryparamnames[i];
						if(KeyColumns.contains(currentparam))
						{
							++indexscore[indexcounter][NumberofColsKeyedInThisIndex];
							int paramindex = indexfile.getKeyColumnIndex(currentparam);
							if (localleftmostindex>paramindex)
								localleftmostindex = paramindex;
						}
					}
					indexscore[indexcounter][LeftMostKeyIndexPosition] = localleftmostindex;
				}

				if (indexscore[indexcounter][NumberOfCommonParamInThisIndex]==expectedretcolumns.size()
						&& indexscore[indexcounter][NumberofColsKeyedInThisIndex] > 0
						&& (!parser.whereClauseContainsOrOperator()))
					payloadIdxWithAtLeast1KeyedFieldFound = true; // during scoring, give this priority
			}
		}

		for (int i = 0; i<relindexes.size(); i++)
		{
			//if (indexscore[i][NumberOfCommonParamInThisIndex] == 0 || indexscore[i][NumberofColsKeyedInThisIndex] == 0) //does one imply the other?
			if (indexscore[i][NumberofColsKeyedInThisIndex] == 0) //does one imply the other?
				continue; //not good enough

			if (payloadIdxWithAtLeast1KeyedFieldFound && indexscore[i][NumberOfCommonParamInThisIndex]<expectedretcolumns.size())
				continue; //not good enough

			if (indexscore[i][NumberofColsKeyedInThisIndex] < parser.getWhereClauseExpressionsCount() && parser.whereClauseContainsOrOperator())
				continue; //not so sure about this rule.

			//TODO if current is not payload index, check for fpos field if not found... discard???
			//int localscore = ((indexscore[i][NumberOfCommonParamInThisIndex]/selectColumns.size()) * 5) -
			int localscore = ((indexscore[i][NumberOfCommonParamInThisIndex]/expectedretcolumns.size()) * 5) -
						 	 (((indexscore[i][LeftMostKeyIndexPosition]/totalparamcount)-1) * 3) +
						 	 ((indexscore[i][NumberofColsKeyedInThisIndex]) * 2);

			if (highscore < localscore )
			{
				highscore = localscore;
				indextouse = relindexes.get(i);
			}
		}

		return indextouse;
	}

//	public DFUFile findAppropriateIndexOLD(List<String> relindexes, List<EclColumnMetaData> expectedretcolumns, HashMap parameters, SqlParser parser)
//	{
//		String indextouse = null;
//		DFUFile indexfiletouse = null;
//		String [] sqlqueryparamnames = parser.getWhereClauseNames();
//
//		int totalparamcount = parser.getWhereClauseExpressionsCount();
//
//		int indexscore [][] = new int 	[relindexes.size()]
//										[INDEXSCORECRITERIA/*[FieldsInIndexCount][LeftMostKeyIndex][ColsKeyedcount]*/];
//		int highscore = Integer.MIN_VALUE;
//		boolean payloadIdxWithAtLeast1KeyedFieldFound = false;
//
//		for (int indexcounter = 0; indexcounter < relindexes.size(); indexcounter++)
//		{
//			String indexname = relindexes.get(indexcounter);
//			DFUFile indexfile = dbMetadata.getDFUFile(indexname);
//
//			if (indexfile!= null && indexfile.isKeyFile() && indexfile.hasValidIdxFilePosField())
//			{
//				for(int j = 0; j < expectedretcolumns.size(); j++)
//				{
//					if(indexfile.containsField(expectedretcolumns.get(j), true))
//						++indexscore[indexcounter][NumberOfCommonParamInThisIndex];
//				}
//
//				if (payloadIdxWithAtLeast1KeyedFieldFound && indexscore[indexcounter][NumberOfCommonParamInThisIndex] == 0)
//					break; //Don't bother with this index
//
//				int localleftmostindex = Integer.MAX_VALUE;
//				Properties KeyColumns = indexfile.getKeyedColumns();
//
//				if(KeyColumns!=null)
//				{
//					for (int i = 0; i < sqlqueryparamnames.length; i++)
//					{
//						String currentparam = sqlqueryparamnames[i];
//						if(KeyColumns.contains(currentparam))
//						{
//							++indexscore[indexcounter][NumberofColsKeyedInThisIndex];
//							int paramindex = indexfile.getKeyColumnIndex(currentparam);
//							if (localleftmostindex>paramindex)
//								localleftmostindex = paramindex;
//						}
//					}
//					indexscore[indexcounter][LeftMostKeyIndexPosition] = localleftmostindex;
//				}
//
//				if (indexscore[indexcounter][NumberOfCommonParamInThisIndex]==expectedretcolumns.size()
//						&& indexscore[indexcounter][NumberofColsKeyedInThisIndex] > 0
//						&& (!parser.whereClauseContainsOrOperator()))
//					payloadIdxWithAtLeast1KeyedFieldFound = true; // during scoring, give this priority
//			}
//		}
//
//		for (int i = 0; i<relindexes.size(); i++)
//		{
//			if (indexscore[i][NumberOfCommonParamInThisIndex] == 0 || indexscore[i][NumberofColsKeyedInThisIndex] == 0) //does one imply the other?
//				continue; //not good enough
//
//			if (payloadIdxWithAtLeast1KeyedFieldFound && indexscore[i][NumberOfCommonParamInThisIndex]<expectedretcolumns.size())
//				continue; //not good enough
//
//			if (indexscore[i][NumberofColsKeyedInThisIndex] < parser.getWhereClauseExpressionsCount() && parser.whereClauseContainsOrOperator())
//				continue; //not so sure about this rule.
//
//			//TODO if current is not payload index, check for fpos field if not found... discard???
//			//int localscore = ((indexscore[i][NumberOfCommonParamInThisIndex]/selectColumns.size()) * 5) -
//			int localscore = ((indexscore[i][NumberOfCommonParamInThisIndex]/expectedretcolumns.size()) * 5) -
//						 	 (((indexscore[i][LeftMostKeyIndexPosition]/totalparamcount)-1) * 3) +
//						 	 ((indexscore[i][NumberofColsKeyedInThisIndex]) * 2);
//
//			if (highscore < localscore )
//			{
//				highscore = localscore;
//				indextouse = relindexes.get(i);
//
//				if (payloadIdxWithAtLeast1KeyedFieldFound && indexscore[i][NumberOfCommonParamInThisIndex]==expectedretcolumns.size())
//					parameters.put("PAYLOADINDEX", "true");
//				else
//					parameters.remove("PAYLOADINDEX");
//			}
//		}
//
//		if (indextouse != null)
//		{
//			indexfiletouse = dbMetadata.getDFUFile(indextouse);
//
//			//indexposfield = indexfiletouse.getIdxFilePosField();
//
//			Vector<String> keyed = new Vector<String>();
//			Vector<String> wild = new Vector<String>();
//
//			//Create keyed and wild string
//			Properties keyedcols = indexfiletouse.getKeyedColumns();
//			for (int i = 1; i <= keyedcols.size(); i++)
//			{
//				String keyedcolname = (String)keyedcols.get(i);
//				if(parser.whereClauseContainsKey(keyedcolname))
//					keyed.add(" " + parser.getExpressionFromName(keyedcolname).toString() + " ");
//				else if (keyed.isEmpty())
//					wild.add(" " + keyedcolname + " ");
//			}
//
//			StringBuilder keyedandwild = new StringBuilder();
//			if (parameters.containsKey("PAYLOADINDEX"))
//			{
//				if (keyed.size()>0)
//				{
//					keyedandwild.append("KEYED( ");
//					for (int i = 0 ; i < keyed.size(); i++)
//					{
//						keyedandwild.append(keyed.get(i));
//						if (i < keyed.size()-1)
//							keyedandwild.append(" AND ");
//					}
//					keyedandwild.append(" )");
//				}
//				if (wild.size()>0)
//				{
//					//TODO should I bother making sure there's a KEYED entry ?
//					for (int i = 0 ; i < wild.size(); i++)
//					{
//						keyedandwild.append(" and WILD( ");
//						keyedandwild.append(wild.get(i));
//						keyedandwild.append(" )");
//					}
//				}
//
//				keyedandwild.append(" and (").append(parser.getWhereClauseString()).append(" )");
//			}
//			else //non-payload just AND the keyed expressions
//			{
//				keyedandwild.append("( ");
//				keyedandwild.append(parser.getWhereClauseString());
//				keyedandwild.append(" )");
//			}
//
//			parameters.put("KEYEDWILD", keyedandwild.toString());
//			((ECLPreparedStatement)statement).setIndexToUse(indexfiletouse.getFullyQualifiedName());
//			//System.out.println("Found index to use: " + indextouse + " field: " + indexposfield);
//		}
//		else
//			System.out.println("No appropriate index found");
//
//		return indexfiletouse;
//	}

	public int getRowCount()
	{
		return rows.size();
	}


	public boolean next() throws SQLException
	{
		index++;
		if (index >= rows.size())
		{
			index--;
			return false;
		} else
		{
			return true;
		}
	}


	public void close() throws SQLException
	{
		closed = true;
		lastResult = new Object(); // not null
	}


	public boolean wasNull() throws SQLException
	{
		return lastResult == null;
	}


	public String getString(int columnIndex) throws SQLException
	{
		//System.out.println("getString( " + columnIndex +")");
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return (String) lastResult;
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public boolean getBoolean(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				//content of row field is Object string, need to get value of string and parse as boolean
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return false;
				return Boolean.parseBoolean(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public byte getByte(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return 0;
				return String.valueOf(lastResult).getBytes()[0];
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public short getShort(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Short
				return Short.parseShort(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public int getInt(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Int
				return Integer.parseInt(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public long getLong(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Long
				return Long.parseLong(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public float getFloat(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Float
				return Float.parseFloat(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public double getDouble(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Double
				return Double.parseDouble(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public BigDecimal getBigDecimal(int columnIndex, int scale)	throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as BigDecimal
				BigDecimal bd = new BigDecimal(String.valueOf(lastResult));
				return bd.setScale(scale);
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public byte[] getBytes(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return String.valueOf(lastResult).getBytes();
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public Date getDate(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as Date
				return Date.valueOf(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public Time getTime(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as Time
				return Time.valueOf(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public Timestamp getTimestamp(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as Timestamp
				return Timestamp.valueOf(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public InputStream getAsciiStream(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return new ByteArrayInputStream(String.valueOf(lastResult).getBytes());
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public InputStream getUnicodeStream(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return new ByteArrayInputStream(String.valueOf(lastResult).getBytes());
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public InputStream getBinaryStream(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return new ByteArrayInputStream(String.valueOf(lastResult).getBytes());
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public String getString(String columnLabel) throws SQLException
	{
		//System.out.println("getString( " + columnLabel +")");
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found: " + columnLabel);

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return null;
				return (String)lastResult;
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public boolean getBoolean(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return false;
				return Boolean.parseBoolean(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public byte getByte(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return 0;
				return String.valueOf(lastResult).getBytes()[0];
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public short getShort(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Short
				return Short.parseShort(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public int getInt(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Int
				return Integer.parseInt(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public long getLong(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Long
				return Long.parseLong(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public float getFloat(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Float
				return Float.parseFloat(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public double getDouble(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int column = resultMetadata.getColumnIndex(columnLabel);

			if (column < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(column - 1);
				if (lastResult == null)
					return 0;
				//content of row field is Object string, need to get value of string and parse as Double
				return Double.parseDouble(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as BigDecimal
				BigDecimal bd = new BigDecimal(String.valueOf(lastResult));
				return bd.setScale(scale);
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public byte[] getBytes(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return String.valueOf(lastResult).getBytes();
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public Date getDate(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as Date
				return Date.valueOf(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public Time getTime(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as Time
				return Time.valueOf(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public Timestamp getTimestamp(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as Timestamp
				return Timestamp.valueOf(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public InputStream getAsciiStream(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return new ByteArrayInputStream(String.valueOf(lastResult).getBytes());
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public InputStream getUnicodeStream(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return new ByteArrayInputStream(String.valueOf(lastResult).getBytes());
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public InputStream getBinaryStream(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return new ByteArrayInputStream(String.valueOf(lastResult).getBytes());
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public SQLWarning getWarnings() throws SQLException
	{
		return warnings.isEmpty() ? null : warnings.get(0);
	}


	public void clearWarnings() throws SQLException
	{
		warnings.clear();
	}


	public String getCursorName() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public ResultSetMetaData getMetaData() throws SQLException
	{
		return resultMetadata;
	}


	public Object getObject(int columnIndex) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			//System.out.println("EclResultSet being queried for tablename: " + resultMetadata.getTableName(0) + " column: " + columnIndex + " total number of columns: " + rows.get(index).size() + " Current row: " + index) ;
			//System.out.println("Columlabel: " + resultMetadata.getColumnLabel(columnIndex));
			//System.out.println("Columlabel: " + resultMetadata.getColumnClassName(columnIndex));
			lastResult = rows.get(index).get(columnIndex - 1);
			if (lastResult == null)
				return null;

			//obj = Class.forName(resultMetadata.getColumnClassName(columnIndex));
			//System.out.println("results object id: " + (Object)this.hashCode());
			//System.out.println("returning object" + (lastResult==null ? ": null" : " of type " + resultMetadata.getColumnClassName(columnIndex) +  " : " + lastResult.toString()));

			return lastResult;
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public Object getObject(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult =  row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				return lastResult;
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public int findColumn(String columnLabel) throws SQLException
	{
		return resultMetadata.getColumnIndex(columnLabel);
	}


	public Reader getCharacterStream(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Reader getCharacterStream(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public BigDecimal getBigDecimal(int columnIndex) throws SQLException
	{
		if (index >= 0 && index <= rows.size())
			if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
			{
				lastResult = rows.get(index).get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as BigDecimal
				return new BigDecimal(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Invalid Column Index");
		else
			throw new SQLException("Invalid Row Index");
	}


	public BigDecimal getBigDecimal(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				lastResult = row.get(columnIndex - 1);
				if (lastResult == null)
					return null;
				//content of row field is Object string, need to get value of string and parse as BigDecimal
				return new BigDecimal(String.valueOf(lastResult));
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public boolean isBeforeFirst() throws SQLException
	{
		return (index < 0) ? true : false;
	}


	public boolean isAfterLast() throws SQLException
	{
		return (index > rows.size() - 1) ? true : false;
	}


	public boolean isFirst() throws SQLException
	{
		return  index == 0 ? true : false;
	}


	public boolean isLast() throws SQLException
	{
		return  (index == rows.size() - 1) ? true : false;
	}


	public void beforeFirst() throws SQLException
	{
		throw new UnsupportedOperationException("Not supported");
	}


	public void afterLast() throws SQLException
	{
		throw new UnsupportedOperationException("Not supported");
	}


	public boolean first() throws SQLException
	{
		if (rows.size() > 0)
		{
			index = 0;
			return true;
		}
		else
			return false;
	}


	public boolean last() throws SQLException
	{
		if (rows.size() > 0)
		{
			index = rows.size() - 1;
			return true;
		}
		else
			return false;
	}


	public int getRow() throws SQLException
	{
		return index + 1;
	}


	public boolean absolute(int row) throws SQLException
	{
		if (row > 0 && row <= rows.size())
		{
			index = row - 1;
			return true;
		} else
		{
			return false;
		}
	}


	public boolean relative(int rows) throws SQLException
	{
		int tmpindex = index + rows;
		if (tmpindex > 0 && tmpindex <= this.rows.size())
		{
			index = tmpindex;
			return true;
		} else
		{
			return false;
		}
	}


	public boolean previous() throws SQLException
	{
		if (index > 1)
		{
			index--;
			return true;
		} else
		{
			return false;
		}
	}


	public void setFetchDirection(int direction) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public int getFetchDirection() throws SQLException
	{
		return ResultSet.FETCH_FORWARD;
	}


	public void setFetchSize(int rows) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public int getFetchSize() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public int getType() throws SQLException
	{
		return ResultSet.TYPE_SCROLL_INSENSITIVE;
	}


	public int getConcurrency() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public boolean rowUpdated() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public boolean rowInserted() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public boolean rowDeleted() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateNull(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBoolean(int columnIndex, boolean x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateByte(int columnIndex, byte x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateShort(int columnIndex, short x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateInt(int columnIndex, int x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateLong(int columnIndex, long x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateFloat(int columnIndex, float x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateDouble(int columnIndex, double x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBigDecimal(int columnIndex, BigDecimal x)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateString(int columnIndex, String x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBytes(int columnIndex, byte[] x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateDate(int columnIndex, Date x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateTime(int columnIndex, Time x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateTimestamp(int columnIndex, Timestamp x)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateAsciiStream(int columnIndex, InputStream x, int length)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBinaryStream(int columnIndex, InputStream x, int length)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateCharacterStream(int columnIndex, Reader x, int length)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateObject(int columnIndex, Object x, int scaleOrLength)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateObject(int columnIndex, Object x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateNull(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBoolean(String columnLabel, boolean x)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateByte(String columnLabel, byte x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateShort(String columnLabel, short x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateInt(String columnLabel, int x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateLong(String columnLabel, long x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateFloat(String columnLabel, float x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateDouble(String columnLabel, double x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBigDecimal(String columnLabel, BigDecimal x)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateString(String columnLabel, String x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBytes(String columnLabel, byte[] x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateDate(String columnLabel, Date x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateTime(String columnLabel, Time x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateTimestamp(String columnLabel, Timestamp x)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateAsciiStream(String columnLabel, InputStream x, int length)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateBinaryStream(String columnLabel, InputStream x, int length)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateCharacterStream(String columnLabel, Reader reader,
			int length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateObject(String columnLabel, Object x, int scaleOrLength)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateObject(String columnLabel, Object x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void insertRow() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateRow() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void deleteRow() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void refreshRow() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void cancelRowUpdates() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void moveToInsertRow() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void moveToCurrentRow() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Statement getStatement() throws SQLException
	{
		return statement;
	}


	public Object getObject(int columnIndex, Map<String, Class<?>> map)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Ref getRef(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Blob getBlob(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Clob getClob(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Array getArray(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Object getObject(String columnLabel, Map<String, Class<?>> map)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Ref getRef(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Blob getBlob(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Clob getClob(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Array getArray(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Date getDate(int columnIndex, Calendar cal) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Date getDate(String columnLabel, Calendar cal) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Time getTime(int columnIndex, Calendar cal) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Time getTime(String columnLabel, Calendar cal) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Timestamp getTimestamp(int columnIndex, Calendar cal)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public Timestamp getTimestamp(String columnLabel, Calendar cal)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public URL getURL(int columnIndex) throws SQLException
	{
		try
		{
			if (index >= 0 && index <= rows.size())
				if (columnIndex >= 1 && columnIndex <= rows.get(index).size())
				{
					lastResult = rows.get(index).get(columnIndex - 1);
					if (lastResult == null)
						return null;
					//content of row field is Object string, need to get value of string and parse as BigDecimal
					return new URL(String.valueOf(lastResult));
				}
				else
					throw new SQLException("Invalid Column Index");
			else
				throw new SQLException("Invalid Row Index");

		} catch (MalformedURLException e)
		{
			throw new SQLException(e.getMessage());
		}
	}


	public URL getURL(String columnLabel) throws SQLException
	{
		if (index >= 0  && index <= rows.size())
		{
			int columnIndex = resultMetadata.getColumnIndex(columnLabel);

			if (columnIndex < 0)
				throw new SQLException("Invalid Column Label found");

			List<?> row = rows.get(index);
			if (row != null)
			{
				try
				{
					lastResult = row.get(columnIndex - 1);
					if (lastResult == null)
						return null;
					return new URL(String.valueOf(lastResult));
				}
				catch (MalformedURLException e)
				{
					throw new SQLException(e.getMessage());
				}
			}
			else
				throw new SQLException("Null Row found");
		}
		else
			throw new SQLException("Invalid Row Index");
	}


	public void updateRef(int columnIndex, Ref x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateRef(int columnIndex, Ref x) Not supported yet.");
	}


	public void updateRef(String columnLabel, Ref x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateRef(String columnLabel, Ref x) Not supported yet.");
	}


	public void updateBlob(int columnIndex, Blob x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBlob(int columnIndex, Blob x)  Not supported yet.");
	}


	public void updateBlob(String columnLabel, Blob x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBlob(String columnLabel, Blob x) Not supported yet.");
	}


	public void updateClob(int columnIndex, Clob x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateClob(int columnIndex, Clob x) Not supported yet.");
	}


	public void updateClob(String columnLabel, Clob x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateClob(String columnLabel, Clob x) Not supported yet.");
	}


	public void updateArray(int columnIndex, Array x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateArray(int columnIndex, Array x) Not supported yet.");
	}


	public void updateArray(String columnLabel, Array x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateArray(String columnLabel, Array x)  Not supported yet.");
	}


	public RowId getRowId(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getRowId(int columnIndex) Not supported yet.");
	}


	public RowId getRowId(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getRowId(String columnLabel) Not supported yet.");
	}


	public void updateRowId(int columnIndex, RowId x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateRowId(int columnIndex, RowId x) Not supported yet.");
	}


	public void updateRowId(String columnLabel, RowId x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateRowId(String columnLabel, RowId x) Not supported yet.");
	}


	public int getHoldability() throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getHoldability() Not supported yet.");
	}


	public boolean isClosed() throws SQLException
	{
		return closed;
	}


	public void updateNString(int columnIndex, String nString)	throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNString(int columnIndex, String nString) Not supported yet.");
	}


	public void updateNString(String columnLabel, String nString)
			throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNString(String columnLabel, String nString) Not supported yet.");
	}


	public void updateNClob(int columnIndex, NClob nClob) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: Not supported yet.");
	}


	public void updateNClob(String columnLabel, NClob nClob) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNClob(String columnLabel, NClob nClob) Not supported yet.");
	}


	public NClob getNClob(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getNClob(int columnIndex) Not supported yet.");
	}


	public NClob getNClob(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getNClob(String columnLabel) Not supported yet.");
	}


	public SQLXML getSQLXML(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getSQLXML(int columnIndex) Not supported yet.");
	}


	public SQLXML getSQLXML(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getSQLXML(String columnLabel) Not supported yet.");
	}


	public void updateSQLXML(int columnIndex, SQLXML xmlObject)	throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateSQLXML(int columnIndex, SQLXML xmlObject) Not supported yet.");
	}


	public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateSQLXML(String columnLabel, SQLXML xmlObject) Not supported yet.");
	}


	public String getNString(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getNString(int columnIndex) Not supported yet.");
	}


	public String getNString(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getNString(String columnLabel) Not supported yet.");
	}


	public Reader getNCharacterStream(int columnIndex) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getNCharacterStream(int columnIndex) Not supported yet.");
	}


	public Reader getNCharacterStream(String columnLabel) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: getNCharacterStream(String columnLabel) Not supported yet.");
	}


	public void updateNCharacterStream(int columnIndex, Reader x, long length)	throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNCharacterStream(int columnIndex, Reader x, long length) Not supported yet.");
	}


	public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNCharacterStream(String columnLabel, Reader reader, long length) Not supported yet.");
	}


	public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateAsciiStream(int columnIndex, InputStream x, long length) Not supported yet.");
	}


	public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBinaryStream(int columnIndex, InputStream x, long length) Not supported yet.");
	}


	public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateCharacterStream(int columnIndex, Reader x, long length) Not supported yet.");
	}


	public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateAsciiStream(String columnLabel, InputStream x, long length) Not supported yet.");
	}


	public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBinaryStream(String columnLabel, InputStream x, long length) Not supported yet.");
	}


	public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateCharacterStream(String columnLabel, Reader reader, long length) Not supported yet.");
	}


	public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBlob(int columnIndex, InputStream inputStream, long length) Not supported yet.");
	}


	public void updateBlob(String columnLabel, InputStream inputStream,	long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBlob(String columnLabel, InputStream inputStream,	long length) Not supported yet.");
	}


	public void updateClob(int columnIndex, Reader reader, long length)	throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateClob(int columnIndex, Reader reader, long length) Not supported yet.");
	}


	public void updateClob(String columnLabel, Reader reader, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateClob(String columnLabel, Reader reader, long length) Not supported yet.");
	}


	public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNClob(int columnIndex, Reader reader, long length) Not supported yet.");
	}


	public void updateNClob(String columnLabel, Reader reader, long length)	throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNClob(String columnLabel, Reader reader, long length) Not supported yet.");
	}


	public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNCharacterStream(int columnIndex, Reader x) Not supported yet.");
	}


	public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNCharacterStream(String columnLabel, Reader reader) Not supported yet.");
	}


	public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateAsciiStream(int columnIndex, InputStream x)Not supported yet.");
	}


	public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBinaryStream Not supported yet.");
	}


	public void updateCharacterStream(int columnIndex, Reader x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateCharacterStream Not supported yet.");
	}


	public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateAsciiStream Not supported yet.");
	}


	public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBinaryStream Not supported yet.");
	}


	public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateCharacterStream Not supported yet.");
	}


	public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBlob(int columnIndex, InputStream inputStream) Not supported yet.");
	}


	public void updateBlob(String columnLabel, InputStream inputStream)	throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateBlob(String columnLabel, InputStream inputStream) Not supported yet.");
	}


	public void updateClob(int columnIndex, Reader reader) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateClob(int columnIndex, Reader reader) Not supported yet.");
	}


	public void updateClob(String columnLabel, Reader reader) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateClob(String columnLabel, Reader reader)  Not supported yet.");
	}


	public void updateNClob(int columnIndex, Reader reader) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNClob Not supported yet.");
	}


	public void updateNClob(String columnLabel, Reader reader) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: updateNClob Not supported yet.");
	}


	public <T> T unwrap(Class<T> iface) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: unwrap Not supported yet.");
	}


	public boolean isWrapperFor(Class<?> iface) throws SQLException
	{
		throw new UnsupportedOperationException("EclResultSet: isWrapperFor Not supported yet.");
	}
}
