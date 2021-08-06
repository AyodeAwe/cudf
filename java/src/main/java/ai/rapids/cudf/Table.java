/*
 *
 *  Copyright (c) 2019-2021, NVIDIA CORPORATION.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package ai.rapids.cudf;

import ai.rapids.cudf.HostColumnVector.BasicType;
import ai.rapids.cudf.HostColumnVector.DataType;
import ai.rapids.cudf.HostColumnVector.ListType;
import ai.rapids.cudf.HostColumnVector.StructData;
import ai.rapids.cudf.HostColumnVector.StructType;
import ai.rapids.cudf.ast.CompiledExpression;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Class to represent a collection of ColumnVectors and operations that can be performed on them
 * collectively.
 * The refcount on the columns will be increased once they are passed in
 */
public final class Table implements AutoCloseable {
  static {
    NativeDepsLoader.loadNativeDeps();
  }

  private final long rows;
  private long nativeHandle;
  private ColumnVector[] columns;

  /**
   * Table class makes a copy of the array of {@link ColumnVector}s passed to it. The class
   * will decrease the refcount
   * on itself and all its contents when closed and free resources if refcount is zero
   * @param columns - Array of ColumnVectors
   */
  public Table(ColumnVector... columns) {
    assert columns != null && columns.length > 0 : "ColumnVectors can't be null or empty";
    rows = columns[0].getRowCount();

    for (ColumnVector columnVector : columns) {
      assert (null != columnVector) : "ColumnVectors can't be null";
      assert (rows == columnVector.getRowCount()) : "All columns should have the same number of " +
          "rows " + columnVector.getType();
    }

    // Since Arrays are mutable objects make a copy
    this.columns = new ColumnVector[columns.length];
    long[] viewPointers = new long[columns.length];
    for (int i = 0; i < columns.length; i++) {
      this.columns[i] = columns[i];
      columns[i].incRefCount();
      viewPointers[i] = columns[i].getNativeView();
    }

    nativeHandle = createCudfTableView(viewPointers);
  }

  /**
   * Create a Table from an array of existing on device cudf::column pointers. Ownership of the
   * columns is transferred to the ColumnVectors held by the new Table. In the case of an exception
   * the columns will be deleted.
   * @param cudfColumns - Array of nativeHandles
   */
  public Table(long[] cudfColumns) {
    assert cudfColumns != null && cudfColumns.length > 0 : "CudfColumns can't be null or empty";
    this.columns = new ColumnVector[cudfColumns.length];
    try {
      for (int i = 0; i < cudfColumns.length; i++) {
        this.columns[i] = new ColumnVector(cudfColumns[i]);
      }
      long[] views = new long[columns.length];
      for (int i = 0; i < columns.length; i++) {
        views[i] = columns[i].getNativeView();
      }
      nativeHandle = createCudfTableView(views);
      this.rows = columns[0].getRowCount();
    } catch (Throwable t) {
      for (int i = 0; i < cudfColumns.length; i++) {
        if (this.columns[i] != null) {
          this.columns[i].close();
        } else {
          ColumnVector.deleteCudfColumn(cudfColumns[i]);
        }
      }
      throw t;
    }
  }

  /**
   * Provides a faster way to get access to the columns. Only to be used internally, and it should
   * never be modified in anyway.
   */
  ColumnVector[] getColumns() {
    return columns;
  }

  /** Return the native table view handle for this table */
  public long getNativeView() {
    return nativeHandle;
  }

  /**
   * Return the {@link ColumnVector} at the specified index. If you want to keep a reference to
   * the column around past the life time of the table, you will need to increment the reference
   * count on the column yourself.
   */
  public ColumnVector getColumn(int index) {
    assert index < columns.length;
    return columns[index];
  }

  public final long getRowCount() {
    return rows;
  }

  public final int getNumberOfColumns() {
    return columns.length;
  }

  @Override
  public void close() {
    if (nativeHandle != 0) {
      deleteCudfTable(nativeHandle);
      nativeHandle = 0;
    }
    if (columns != null) {
      for (int i = 0; i < columns.length; i++) {
        columns[i].close();
        columns[i] = null;
      }
      columns = null;
    }
  }

  @Override
  public String toString() {
    return "Table{" +
        "columns=" + Arrays.toString(columns) +
        ", cudfTable=" + nativeHandle +
        ", rows=" + rows +
        '}';
  }

  /**
   * Returns the Device memory buffer size.
   */
  public long getDeviceMemorySize() {
    long total = 0;
    for (ColumnVector cv: columns) {
      total += cv.getDeviceMemorySize();
    }
    return total;
  }

  /////////////////////////////////////////////////////////////////////////////
  // NATIVE APIs
  /////////////////////////////////////////////////////////////////////////////
  
  private static native ContiguousTable[] contiguousSplit(long inputTable, int[] indices);

  private static native long[] partition(long inputTable, long partitionView,
      int numberOfPartitions, int[] outputOffsets);

  private static native long[] hashPartition(long inputTable,
                                             int[] columnsToHash,
                                             int hashTypeId,
                                             int numberOfPartitions,
                                             int[] outputOffsets) throws CudfException;

  private static native long[] roundRobinPartition(long inputTable,
                                                   int numberOfPartitions,
                                                   int startPartition,
                                                   int[] outputOffsets) throws CudfException;

  private static native void deleteCudfTable(long handle) throws CudfException;

  private static native long bound(long inputTable, long valueTable,
                                   boolean[] descFlags, boolean[] areNullsSmallest, boolean isUpperBound) throws CudfException;

  /**
   * Ugly long function to read CSV.  This is a long function to avoid the overhead of reaching
   * into a java
   * object to try and pull out all of the options.  If this becomes unwieldy we can change it.
   * @param columnNames       names of all of the columns, even the ones filtered out
   * @param dTypes            types of all of the columns as strings.  Why strings? who knows.
   * @param filterColumnNames name of the columns to read, or an empty array if we want to read
   *                          all of them
   * @param filePath          the path of the file to read, or null if no path should be read.
   * @param address           the address of the buffer to read from or 0 if we should not.
   * @param length            the length of the buffer to read from.
   * @param headerRow         the 0 based index row of the header can be -1
   * @param delim             character deliminator (must be ASCII).
   * @param quote             character quote (must be ASCII).
   * @param comment           character that starts a comment line (must be ASCII) use '\0'
   * @param nullValues        values that should be treated as nulls
   * @param trueValues        values that should be treated as boolean true
   * @param falseValues       values that should be treated as boolean false
   */
  private static native long[] readCSV(String[] columnNames, String[] dTypes,
                                       String[] filterColumnNames,
                                       String filePath, long address, long length,
                                       int headerRow, byte delim, byte quote,
                                       byte comment, String[] nullValues,
                                       String[] trueValues, String[] falseValues) throws CudfException;

  /**
   * Read in Parquet formatted data.
   * @param filterColumnNames  name of the columns to read, or an empty array if we want to read
   *                           all of them
   * @param filePath           the path of the file to read, or null if no path should be read.
   * @param address            the address of the buffer to read from or 0 if we should not.
   * @param length             the length of the buffer to read from.
   * @param timeUnit           return type of TimeStamp in units
   * @param strictDecimalTypes whether strictly reading all decimal columns as fixed-point decimal type
   */
  private static native long[] readParquet(String[] filterColumnNames, String filePath,
                                           long address, long length, int timeUnit,
                                           boolean strictDecimalTypes) throws CudfException;

  /**
   * Setup everything to write parquet formatted data to a file.
   * @param columnNames     names that correspond to the table columns
   * @param numChildren     Children of the top level
   * @param flatNumChildren flattened list of children per column
   * @param nullable        true if the column can have nulls else false
   * @param metadataKeys    Metadata key names to place in the Parquet file
   * @param metadataValues  Metadata values corresponding to metadataKeys
   * @param compression     native compression codec ID
   * @param statsFreq       native statistics frequency ID
   * @param isInt96         true if timestamp type is int96
   * @param precisions      precision list containing all the precisions of the decimal types in
   *                        the columns
   * @param filename        local output path
   * @return a handle that is used in later calls to writeParquetChunk and writeParquetEnd.
   */
  private static native long writeParquetFileBegin(String[] columnNames,
                                                   int numChildren,
                                                   int[] flatNumChildren,
                                                   boolean[] nullable,
                                                   String[] metadataKeys,
                                                   String[] metadataValues,
                                                   int compression,
                                                   int statsFreq,
                                                   boolean[] isInt96,
                                                   int[] precisions,
                                                   String filename) throws CudfException;

  /**
   * Setup everything to write parquet formatted data to a buffer.
   * @param columnNames     names that correspond to the table columns
   * @param numChildren     Children of the top level
   * @param flatNumChildren flattened list of children per column
   * @param nullable        true if the column can have nulls else false
   * @param metadataKeys    Metadata key names to place in the Parquet file
   * @param metadataValues  Metadata values corresponding to metadataKeys
   * @param compression     native compression codec ID
   * @param statsFreq       native statistics frequency ID
   * @param isInt96         true if timestamp type is int96
   * @param precisions      precision list containing all the precisions of the decimal types in
   *                        the columns
   * @param consumer        consumer of host buffers produced.
   * @return a handle that is used in later calls to writeParquetChunk and writeParquetEnd.
   */
  private static native long writeParquetBufferBegin(String[] columnNames,
                                                     int numChildren,
                                                     int[] flatNumChildren,
                                                     boolean[] nullable,
                                                     String[] metadataKeys,
                                                     String[] metadataValues,
                                                     int compression,
                                                     int statsFreq,
                                                     boolean[] isInt96,
                                                     int[] precisions,
                                                     HostBufferConsumer consumer) throws CudfException;

  /**
   * Write out a table to an open handle.
   * @param handle the handle to the writer.
   * @param table the table to write out.
   * @param tableMemSize the size of the table in bytes to help with memory allocation.
   */
  private static native void writeParquetChunk(long handle, long table, long tableMemSize);

  /**
   * Finish writing out parquet.
   * @param handle the handle.  Do not use again once this returns.
   */
  private static native void writeParquetEnd(long handle);

  /**
   * Read in ORC formatted data.
   * @param filterColumnNames name of the columns to read, or an empty array if we want to read
   *                          all of them
   * @param filePath          the path of the file to read, or null if no path should be read.
   * @param address           the address of the buffer to read from or 0 for no buffer.
   * @param length            the length of the buffer to read from.
   * @param usingNumPyTypes   whether the parser should implicitly promote TIMESTAMP
   *                          columns to TIMESTAMP_MILLISECONDS for compatibility with NumPy.
   * @param timeUnit          return type of TimeStamp in units
   */
  private static native long[] readORC(String[] filterColumnNames,
                                       String filePath, long address, long length,
                                       boolean usingNumPyTypes, int timeUnit) throws CudfException;

  /**
   * Setup everything to write ORC formatted data to a file.
   * @param columnNames     names that correspond to the table columns
   * @param nullable        true if the column can have nulls else false
   * @param metadataKeys    Metadata key names to place in the Parquet file
   * @param metadataValues  Metadata values corresponding to metadataKeys
   * @param compression     native compression codec ID
   * @param filename        local output path
   * @return a handle that is used in later calls to writeORCChunk and writeORCEnd.
   */
  private static native long writeORCFileBegin(String[] columnNames,
                                               boolean[] nullable,
                                               String[] metadataKeys,
                                               String[] metadataValues,
                                               int compression,
                                               String filename) throws CudfException;

  /**
   * Setup everything to write ORC formatted data to a buffer.
   * @param columnNames     names that correspond to the table columns
   * @param nullable        true if the column can have nulls else false
   * @param metadataKeys    Metadata key names to place in the Parquet file
   * @param metadataValues  Metadata values corresponding to metadataKeys
   * @param compression     native compression codec ID
   * @param consumer        consumer of host buffers produced.
   * @return a handle that is used in later calls to writeORCChunk and writeORCEnd.
   */
  private static native long writeORCBufferBegin(String[] columnNames,
                                                 boolean[] nullable,
                                                 String[] metadataKeys,
                                                 String[] metadataValues,
                                                 int compression,
                                                 HostBufferConsumer consumer) throws CudfException;

  /**
   * Write out a table to an open handle.
   * @param handle the handle to the writer.
   * @param table the table to write out.
   * @param tableMemSize the size of the table in bytes to help with memory allocation.
   */
  private static native void writeORCChunk(long handle, long table, long tableMemSize);

  /**
   * Finish writing out ORC.
   * @param handle the handle.  Do not use again once this returns.
   */
  private static native void writeORCEnd(long handle);

  /**
   * Setup everything to write Arrow IPC formatted data to a file.
   * @param columnNames names that correspond to the table columns
   * @param filename local output path
   * @return a handle that is used in later calls to writeArrowIPCChunk and writeArrowIPCEnd.
   */
  private static native long writeArrowIPCFileBegin(String[] columnNames, String filename);

  /**
   * Setup everything to write Arrow IPC formatted data to a buffer.
   * @param columnNames names that correspond to the table columns
   * @param consumer consumer of host buffers produced.
   * @return a handle that is used in later calls to writeArrowIPCChunk and writeArrowIPCEnd.
   */
  private static native long writeArrowIPCBufferBegin(String[] columnNames,
                                                      HostBufferConsumer consumer);

  /**
   * Convert a cudf table to an arrow table handle.
   * @param handle the handle to the writer.
   * @param tableHandle the table to convert
   */
  private static native long convertCudfToArrowTable(long handle,
                                                     long tableHandle);

  /**
   * Write out a table to an open handle.
   * @param handle the handle to the writer.
   * @param arrowHandle the arrow table to write out.
   * @param maxChunkSize the maximum number of rows that could
   *                     be written out in a single chunk.  Generally this setting will be
   *                     followed unless for some reason the arrow table is not a single group.
   *                     This can happen when reading arrow data, but not when converting from
   *                     cudf.
   */
  private static native void writeArrowIPCArrowChunk(long handle,
                                                     long arrowHandle,
                                                     long maxChunkSize);

  /**
   * Finish writing out Arrow IPC.
   * @param handle the handle.  Do not use again once this returns.
   */
  private static native void writeArrowIPCEnd(long handle);

  /**
   * Setup everything to read an Arrow IPC formatted data file.
   * @param path local input path
   * @return a handle that is used in later calls to readArrowIPCChunk and readArrowIPCEnd.
   */
  private static native long readArrowIPCFileBegin(String path);

  /**
   * Setup everything to read Arrow IPC formatted data from a provider.
   * @param provider the class that will provide the data.
   * @return a handle that is used in later calls to readArrowIPCChunk and readArrowIPCEnd.
   */
  private static native long readArrowIPCBufferBegin(ArrowReaderWrapper provider);

  /**
   * Read the next chunk/table of data.
   * @param handle the handle that is holding the data.
   * @param rowTarget the number of rows to read.
   * @return a pointer to an arrow table handle.
   */
  private static native long readArrowIPCChunkToArrowTable(long handle, int rowTarget);

  /**
   * Close the arrow table handle returned by readArrowIPCChunkToArrowTable or
   * convertCudfToArrowTable
   */
  private static native void closeArrowTable(long arrowHandle);

  /**
   * Convert an arrow table handle as returned by readArrowIPCChunkToArrowTable to
   * cudf table handles.
   */
  private static native long[] convertArrowTableToCudf(long arrowHandle);

  /**
   * Finish reading the data.  We are done.
   * @param handle the handle to clean up.
   */
  private static native void readArrowIPCEnd(long handle);

  private static native long[] groupByAggregate(long inputTable, int[] keyIndices, int[] aggColumnsIndices,
                                                long[] aggInstances, boolean ignoreNullKeys,
                                                boolean keySorted, boolean[] keysDescending,
                                                boolean[] keysNullSmallest) throws CudfException;

  private static native long[] groupByScan(long inputTable, int[] keyIndices, int[] aggColumnsIndices,
      long[] aggInstances, boolean ignoreNullKeys,
      boolean keySorted, boolean[] keysDescending,
      boolean[] keysNullSmallest) throws CudfException;

  private static native long[] groupByReplaceNulls(long inputTable, int[] keyIndices,
      int[] replaceColumnsIndices,
      boolean[] isPreceding, boolean ignoreNullKeys,
      boolean keySorted, boolean[] keysDescending,
      boolean[] keysNullSmallest) throws CudfException;

  private static native long[] rollingWindowAggregate(
      long inputTable,
      int[] keyIndices,
      long[] defaultOutputs,
      int[] aggColumnsIndices,
      long[] aggInstances,
      int[] minPeriods,
      int[] preceding,
      int[] following,
      boolean ignoreNullKeys) throws CudfException;

  private static native long[] rangeRollingWindowAggregate(long inputTable, int[] keyIndices, int[] orderByIndices, boolean[] isOrderByAscending,
                                                           int[] aggColumnsIndices, long[] aggInstances, int[] minPeriods,
                                                           long[] preceding, long[] following, boolean[] unboundedPreceding, boolean[] unboundedFollowing,
                                                           boolean ignoreNullKeys) throws CudfException;

  private static native long sortOrder(long inputTable, long[] sortKeys, boolean[] isDescending,
      boolean[] areNullsSmallest) throws CudfException;

  private static native long[] orderBy(long inputTable, long[] sortKeys, boolean[] isDescending,
                                       boolean[] areNullsSmallest) throws CudfException;

  private static native long[] merge(long[] tableHandles, int[] sortKeyIndexes,
                                     boolean[] isDescending, boolean[] areNullsSmallest) throws CudfException;

  private static native long[] leftJoin(long leftTable, int[] leftJoinCols, long rightTable,
                                        int[] rightJoinCols, boolean compareNullsEqual) throws CudfException;

  private static native long[] leftJoinGatherMaps(long leftKeys, long rightKeys,
                                                  boolean compareNullsEqual) throws CudfException;

  private static native long[] innerJoin(long leftTable, int[] leftJoinCols, long rightTable,
                                         int[] rightJoinCols, boolean compareNullsEqual) throws CudfException;

  private static native long[] innerJoinGatherMaps(long leftKeys, long rightKeys,
                                                   boolean compareNullsEqual) throws CudfException;

  private static native long[] fullJoin(long leftTable, int[] leftJoinCols, long rightTable,
                                         int[] rightJoinCols, boolean compareNullsEqual) throws CudfException;

  private static native long[] fullJoinGatherMaps(long leftKeys, long rightKeys,
                                                  boolean compareNullsEqual) throws CudfException;

  private static native long[] leftSemiJoin(long leftTable, int[] leftJoinCols, long rightTable,
      int[] rightJoinCols, boolean compareNullsEqual) throws CudfException;

  private static native long[] leftSemiJoinGatherMap(long leftKeys, long rightKeys,
                                                     boolean compareNullsEqual) throws CudfException;

  private static native long[] leftAntiJoin(long leftTable, int[] leftJoinCols, long rightTable,
      int[] rightJoinCols, boolean compareNullsEqual) throws CudfException;

  private static native long[] leftAntiJoinGatherMap(long leftKeys, long rightKeys,
                                                     boolean compareNullsEqual) throws CudfException;

  private static native long[] conditionalLeftJoinGatherMaps(long leftTable, long rightTable,
                                                             long condition,
                                                             boolean compareNullsEqual) throws CudfException;

  private static native long[] conditionalInnerJoinGatherMaps(long leftTable, long rightTable,
                                                              long condition,
                                                              boolean compareNullsEqual) throws CudfException;

  private static native long[] conditionalFullJoinGatherMaps(long leftTable, long rightTable,
                                                             long condition,
                                                             boolean compareNullsEqual) throws CudfException;

  private static native long[] conditionalLeftSemiJoinGatherMap(long leftTable, long rightTable,
                                                                long condition,
                                                                boolean compareNullsEqual) throws CudfException;

  private static native long[] conditionalLeftAntiJoinGatherMap(long leftTable, long rightTable,
                                                                long condition,
                                                                boolean compareNullsEqual) throws CudfException;

  private static native long[] crossJoin(long leftTable, long rightTable) throws CudfException;

  private static native long[] concatenate(long[] cudfTablePointers) throws CudfException;

  private static native long interleaveColumns(long input);

  private static native long[] filter(long input, long mask);

  private static native long[] gather(long tableHandle, long gatherView, boolean checkBounds);

  private static native long[] convertToRows(long nativeHandle);

  private static native long[] convertFromRows(long nativeColumnView, int[] types, int[] scale);

  private static native long[] repeatStaticCount(long tableHandle, int count);

  private static native long[] repeatColumnCount(long tableHandle,
                                                 long columnHandle,
                                                 boolean checkCount);

  private static native long rowBitCount(long tableHandle) throws CudfException;

  private static native long[] explode(long tableHandle, int index);

  private static native long[] explodePosition(long tableHandle, int index);

  private static native long[] explodeOuter(long tableHandle, int index);

  private static native long[] explodeOuterPosition(long tableHandle, int index);

  private static native long createCudfTableView(long[] nativeColumnViewHandles);

  private static native long[] columnViewsFromPacked(ByteBuffer metadata, long dataAddress);

  private static native ContiguousTable[] contiguousSplitGroups(long inputTable,
                                                                int[] keyIndices,
                                                                boolean ignoreNullKeys,
                                                                boolean keySorted,
                                                                boolean[] keysDescending,
                                                                boolean[] keysNullSmallest);

  /////////////////////////////////////////////////////////////////////////////
  // TABLE CREATION APIs
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Read a CSV file using the default CSVOptions.
   * @param schema the schema of the file.  You may use Schema.INFERRED to infer the schema.
   * @param path the local file to read.
   * @return the file parsed as a table on the GPU.
   */
  public static Table readCSV(Schema schema, File path) {
    return readCSV(schema, CSVOptions.DEFAULT, path);
  }

  /**
   * Read a CSV file.
   * @param schema the schema of the file.  You may use Schema.INFERRED to infer the schema.
   * @param opts various CSV parsing options.
   * @param path the local file to read.
   * @return the file parsed as a table on the GPU.
   */
  public static Table readCSV(Schema schema, CSVOptions opts, File path) {
    return new Table(
        readCSV(schema.getColumnNames(), schema.getTypesAsStrings(),
            opts.getIncludeColumnNames(), path.getAbsolutePath(),
            0, 0,
            opts.getHeaderRow(),
            opts.getDelim(),
            opts.getQuote(),
            opts.getComment(),
            opts.getNullValues(),
            opts.getTrueValues(),
            opts.getFalseValues()));
  }

  /**
   * Read CSV formatted data using the default CSVOptions.
   * @param schema the schema of the data. You may use Schema.INFERRED to infer the schema.
   * @param buffer raw UTF8 formatted bytes.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readCSV(Schema schema, byte[] buffer) {
    return readCSV(schema, CSVOptions.DEFAULT, buffer, 0, buffer.length);
  }

  /**
   * Read CSV formatted data.
   * @param schema the schema of the data. You may use Schema.INFERRED to infer the schema.
   * @param opts various CSV parsing options.
   * @param buffer raw UTF8 formatted bytes.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readCSV(Schema schema, CSVOptions opts, byte[] buffer) {
    return readCSV(schema, opts, buffer, 0, buffer.length);
  }

  /**
   * Read CSV formatted data.
   * @param schema the schema of the data. You may use Schema.INFERRED to infer the schema.
   * @param opts various CSV parsing options.
   * @param buffer raw UTF8 formatted bytes.
   * @param offset the starting offset into buffer.
   * @param len the number of bytes to parse.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readCSV(Schema schema, CSVOptions opts, byte[] buffer, long offset,
                              long len) {
    if (len <= 0) {
      len = buffer.length - offset;
    }
    assert len > 0;
    assert len <= buffer.length - offset;
    assert offset >= 0 && offset < buffer.length;
    try (HostMemoryBuffer newBuf = HostMemoryBuffer.allocate(len)) {
      newBuf.setBytes(0, buffer, offset, len);
      return readCSV(schema, opts, newBuf, 0, len);
    }
  }

  /**
   * Read CSV formatted data.
   * @param schema the schema of the data. You may use Schema.INFERRED to infer the schema.
   * @param opts various CSV parsing options.
   * @param buffer raw UTF8 formatted bytes.
   * @param offset the starting offset into buffer.
   * @param len the number of bytes to parse.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readCSV(Schema schema, CSVOptions opts, HostMemoryBuffer buffer,
                              long offset, long len) {
    if (len <= 0) {
      len = buffer.length - offset;
    }
    assert len > 0;
    assert len <= buffer.getLength() - offset;
    assert offset >= 0 && offset < buffer.length;
    return new Table(readCSV(schema.getColumnNames(), schema.getTypesAsStrings(),
        opts.getIncludeColumnNames(), null,
        buffer.getAddress() + offset, len,
        opts.getHeaderRow(),
        opts.getDelim(),
        opts.getQuote(),
        opts.getComment(),
        opts.getNullValues(),
        opts.getTrueValues(),
        opts.getFalseValues()));
  }

  /**
   * Read a Parquet file using the default ParquetOptions.
   * @param path the local file to read.
   * @return the file parsed as a table on the GPU.
   */
  public static Table readParquet(File path) {
    return readParquet(ParquetOptions.DEFAULT, path);
  }

  /**
   * Read a Parquet file.
   * @param opts various parquet parsing options.
   * @param path the local file to read.
   * @return the file parsed as a table on the GPU.
   */
  public static Table readParquet(ParquetOptions opts, File path) {
    return new Table(readParquet(opts.getIncludeColumnNames(),
        path.getAbsolutePath(), 0, 0, opts.timeUnit().typeId.getNativeId(),
        opts.isStrictDecimalType()));
  }

  /**
   * Read parquet formatted data.
   * @param buffer raw parquet formatted bytes.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readParquet(byte[] buffer) {
    return readParquet(ParquetOptions.DEFAULT, buffer, 0, buffer.length);
  }

  /**
   * Read parquet formatted data.
   * @param opts various parquet parsing options.
   * @param buffer raw parquet formatted bytes.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readParquet(ParquetOptions opts, byte[] buffer) {
    return readParquet(opts, buffer, 0, buffer.length);
  }

  /**
   * Read parquet formatted data.
   * @param opts various parquet parsing options.
   * @param buffer raw parquet formatted bytes.
   * @param offset the starting offset into buffer.
   * @param len the number of bytes to parse.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readParquet(ParquetOptions opts, byte[] buffer, long offset, long len) {
    if (len <= 0) {
      len = buffer.length - offset;
    }
    assert len > 0;
    assert len <= buffer.length - offset;
    assert offset >= 0 && offset < buffer.length;
    try (HostMemoryBuffer newBuf = HostMemoryBuffer.allocate(len)) {
      newBuf.setBytes(0, buffer, offset, len);
      return readParquet(opts, newBuf, 0, len);
    }
  }

  /**
   * Read parquet formatted data.
   * @param opts various parquet parsing options.
   * @param buffer raw parquet formatted bytes.
   * @param offset the starting offset into buffer.
   * @param len the number of bytes to parse.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readParquet(ParquetOptions opts, HostMemoryBuffer buffer,
                                  long offset, long len) {
    if (len <= 0) {
      len = buffer.length - offset;
    }
    assert len > 0;
    assert len <= buffer.getLength() - offset;
    assert offset >= 0 && offset < buffer.length;
    return new Table(readParquet(opts.getIncludeColumnNames(),
        null, buffer.getAddress() + offset, len, opts.timeUnit().typeId.getNativeId(),
        opts.isStrictDecimalType()));
  }

  /**
   * Read a ORC file using the default ORCOptions.
   * @param path the local file to read.
   * @return the file parsed as a table on the GPU.
   */
  public static Table readORC(File path) {
    return readORC(ORCOptions.DEFAULT, path);
  }

  /**
   * Read a ORC file.
   * @param opts ORC parsing options.
   * @param path the local file to read.
   * @return the file parsed as a table on the GPU.
   */
  public static Table readORC(ORCOptions opts, File path) {
    return new Table(readORC(opts.getIncludeColumnNames(),
        path.getAbsolutePath(), 0, 0, opts.usingNumPyTypes(), opts.timeUnit().typeId.getNativeId()));
  }

  /**
   * Read ORC formatted data.
   * @param buffer raw ORC formatted bytes.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readORC(byte[] buffer) {
    return readORC(ORCOptions.DEFAULT, buffer, 0, buffer.length);
  }

  /**
   * Read ORC formatted data.
   * @param opts various ORC parsing options.
   * @param buffer raw ORC formatted bytes.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readORC(ORCOptions opts, byte[] buffer) {
    return readORC(opts, buffer, 0, buffer.length);
  }

  /**
   * Read ORC formatted data.
   * @param opts various ORC parsing options.
   * @param buffer raw ORC formatted bytes.
   * @param offset the starting offset into buffer.
   * @param len the number of bytes to parse.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readORC(ORCOptions opts, byte[] buffer, long offset, long len) {
    if (len <= 0) {
      len = buffer.length - offset;
    }
    assert len > 0;
    assert len <= buffer.length - offset;
    assert offset >= 0 && offset < buffer.length;
    try (HostMemoryBuffer newBuf = HostMemoryBuffer.allocate(len)) {
      newBuf.setBytes(0, buffer, offset, len);
      return readORC(opts, newBuf, 0, len);
    }
  }

  /**
   * Read ORC formatted data.
   * @param opts various ORC parsing options.
   * @param buffer raw ORC formatted bytes.
   * @param offset the starting offset into buffer.
   * @param len the number of bytes to parse.
   * @return the data parsed as a table on the GPU.
   */
  public static Table readORC(ORCOptions opts, HostMemoryBuffer buffer,
                              long offset, long len) {
    if (len <= 0) {
      len = buffer.length - offset;
    }
    assert len > 0;
    assert len <= buffer.getLength() - offset;
    assert offset >= 0 && offset < buffer.length;
    return new Table(readORC(opts.getIncludeColumnNames(),
        null, buffer.getAddress() + offset, len, opts.usingNumPyTypes(),
        opts.timeUnit().typeId.getNativeId()));
  }

  private static class ParquetTableWriter implements TableWriter {
    private long handle;
    HostBufferConsumer consumer;

    private ParquetTableWriter(ParquetWriterOptions options, File outputFile) {
      String[] columnNames = options.getFlatColumnNames();
      boolean[] columnNullabilities = options.getFlatIsNullable();
      boolean[] timeInt96Values = options.getFlatIsTimeTypeInt96();
      int[] precisions = options.getFlatPrecision();
      int[] flatNumChildren = options.getFlatNumChildren();

      this.consumer = null;
      this.handle = writeParquetFileBegin(columnNames,
          options.getTopLevelChildren(),
          flatNumChildren,
          columnNullabilities,
          options.getMetadataKeys(),
          options.getMetadataValues(),
          options.getCompressionType().nativeId,
          options.getStatisticsFrequency().nativeId,
          timeInt96Values,
          precisions,
          outputFile.getAbsolutePath());
    }

    private ParquetTableWriter(ParquetWriterOptions options, HostBufferConsumer consumer) {
      String[] columnNames = options.getFlatColumnNames();
      boolean[] columnNullabilities = options.getFlatIsNullable();
      boolean[] timeInt96Values = options.getFlatIsTimeTypeInt96();
      int[] precisions = options.getFlatPrecision();
      int[] flatNumChildren = options.getFlatNumChildren();

      this.consumer = consumer;
      this.handle = writeParquetBufferBegin(columnNames,
          options.getTopLevelChildren(),
          flatNumChildren,
          columnNullabilities,
          options.getMetadataKeys(),
          options.getMetadataValues(),
          options.getCompressionType().nativeId,
          options.getStatisticsFrequency().nativeId,
          timeInt96Values,
          precisions,
          consumer);
    }

    @Override
    public void write(Table table) {
      if (handle == 0) {
        throw new IllegalStateException("Writer was already closed");
      }
      writeParquetChunk(handle, table.nativeHandle, table.getDeviceMemorySize());
    }

    @Override
    public void close() throws CudfException {
      if (handle != 0) {
        writeParquetEnd(handle);
      }
      handle = 0;
      if (consumer != null) {
        consumer.done();
        consumer = null;
      }
    }
  }

  /**
   * Get a table writer to write parquet data to a file.
   * @param options the parquet writer options.
   * @param outputFile where to write the file.
   * @return a table writer to use for writing out multiple tables.
   */
  public static TableWriter writeParquetChunked(ParquetWriterOptions options, File outputFile) {
    return new ParquetTableWriter(options, outputFile);
  }

  /**
   * Get a table writer to write parquet data and handle each chunk with a callback.
   * @param options the parquet writer options.
   * @param consumer a class that will be called when host buffers are ready with parquet
   *                 formatted data in them.
   * @return a table writer to use for writing out multiple tables.
   */
  public static TableWriter writeParquetChunked(ParquetWriterOptions options,
                                                HostBufferConsumer consumer) {
    return new ParquetTableWriter(options, consumer);
  }

  /**
   * This is an evolving API and most likely be removed in future releases. Please use with the
   * caveat that this will not exist in the near future.
   * @param options the Parquet writer options.
   * @param consumer a class that will be called when host buffers are ready with Parquet
   *                 formatted data in them.
   * @param columnViews ColumnViews to write to Parquet
   */
  public static void writeColumnViewsToParquet(ParquetWriterOptions options,
                                               HostBufferConsumer consumer,
                                               ColumnView... columnViews) {
    assert columnViews != null && columnViews.length > 0 : "ColumnViews can't be null or empty";
    long rows = columnViews[0].getRowCount();

    for (ColumnView columnView : columnViews) {
      assert (null != columnView) : "ColumnViews can't be null";
      assert (rows == columnView.getRowCount()) : "All columns should have the same number of " +
          "rows " + columnView.getType();
    }

    // Since Arrays are mutable objects make a copy
    long[] viewPointers = new long[columnViews.length];
    for (int i = 0; i < columnViews.length; i++) {
      viewPointers[i] = columnViews[i].getNativeView();
    }

    long nativeHandle = createCudfTableView(viewPointers);
    try {
      try (ParquetTableWriter writer = new ParquetTableWriter(options, consumer)) {
        long total = 0;
        for (ColumnView cv : columnViews) {
          total += cv.getDeviceMemorySize();
        }
        writeParquetChunk(writer.handle, nativeHandle, total);
      }
    } finally {
      deleteCudfTable(nativeHandle);
    }
  }

  /**
   * Writes this table to a Parquet file on the host
   *
   * @param options parameters for the writer
   * @param outputFile file to write the table to
   * @deprecated please use writeParquetChunked instead
   */
  @Deprecated
  public void writeParquet(ParquetWriterOptions options, File outputFile) {
    try (TableWriter writer = writeParquetChunked(options, outputFile)) {
      writer.write(this);
    }
  }

  private static class ORCTableWriter implements TableWriter {
    private long handle;
    HostBufferConsumer consumer;

    private ORCTableWriter(ORCWriterOptions options, File outputFile) {
      this.handle = writeORCFileBegin(options.getColumnNames(),
          options.getColumnNullability(),
          options.getMetadataKeys(),
          options.getMetadataValues(),
          options.getCompressionType().nativeId,
          outputFile.getAbsolutePath());
      this.consumer = null;
    }

    private ORCTableWriter(ORCWriterOptions options, HostBufferConsumer consumer) {
      this.handle = writeORCBufferBegin(options.getColumnNames(),
          options.getColumnNullability(),
          options.getMetadataKeys(),
          options.getMetadataValues(),
          options.getCompressionType().nativeId,
          consumer);
      this.consumer = consumer;
    }

    @Override
    public void write(Table table) {
      if (handle == 0) {
        throw new IllegalStateException("Writer was already closed");
      }
      writeORCChunk(handle, table.nativeHandle, table.getDeviceMemorySize());
    }

    @Override
    public void close() throws CudfException {
      if (handle != 0) {
        writeORCEnd(handle);
      }
      handle = 0;
      if (consumer != null) {
        consumer.done();
        consumer = null;
      }
    }
  }

  /**
   * Get a table writer to write ORC data to a file.
   * @param options the ORC writer options.
   * @param outputFile where to write the file.
   * @return a table writer to use for writing out multiple tables.
   */
  public static TableWriter writeORCChunked(ORCWriterOptions options, File outputFile) {
    return new ORCTableWriter(options, outputFile);
  }

  /**
   * Get a table writer to write ORC data and handle each chunk with a callback.
   * @param options the ORC writer options.
   * @param consumer a class that will be called when host buffers are ready with ORC
   *                 formatted data in them.
   * @return a table writer to use for writing out multiple tables.
   */
  public static TableWriter writeORCChunked(ORCWriterOptions options, HostBufferConsumer consumer) {
    return new ORCTableWriter(options, consumer);
  }

  /**
   * Writes this table to a file on the host.
   * @param outputFile - File to write the table to
   * @deprecated please use writeORCChunked instead
   */
  @Deprecated
  public void writeORC(File outputFile) {
    writeORC(ORCWriterOptions.DEFAULT, outputFile);
  }

  /**
   * Writes this table to a file on the host.
   * @param outputFile - File to write the table to
   * @deprecated please use writeORCChunked instead
   */
  @Deprecated
  public void writeORC(ORCWriterOptions options, File outputFile) {
    try (TableWriter writer = Table.writeORCChunked(options, outputFile)) {
      writer.write(this);
    }
  }

  private static class ArrowIPCTableWriter implements TableWriter {
    private final ArrowIPCWriterOptions.DoneOnGpu callback;
    private long handle;
    private HostBufferConsumer consumer;
    private long maxChunkSize;

    private ArrowIPCTableWriter(ArrowIPCWriterOptions options,
                                File outputFile) {
      this.callback = options.getCallback();
      this.consumer = null;
      this.maxChunkSize = options.getMaxChunkSize();
      this.handle = writeArrowIPCFileBegin(
              options.getColumnNames(),
              outputFile.getAbsolutePath());
    }

    private ArrowIPCTableWriter(ArrowIPCWriterOptions options,
                                HostBufferConsumer consumer) {
      this.callback = options.getCallback();
      this.consumer = consumer;
      this.maxChunkSize = options.getMaxChunkSize();
      this.handle = writeArrowIPCBufferBegin(
              options.getColumnNames(),
              consumer);
    }

    @Override
    public void write(Table table) {
      if (handle == 0) {
        throw new IllegalStateException("Writer was already closed");
      }
      long arrowHandle = convertCudfToArrowTable(handle, table.nativeHandle);
      try {
        callback.doneWithTheGpu(table);
        writeArrowIPCArrowChunk(handle, arrowHandle, maxChunkSize);
      } finally {
        closeArrowTable(arrowHandle);
      }
    }

    @Override
    public void close() throws CudfException {
      if (handle != 0) {
        writeArrowIPCEnd(handle);
      }
      handle = 0;
      if (consumer != null) {
        consumer.done();
        consumer = null;
      }
    }
  }

  /**
   * Get a table writer to write arrow IPC data to a file.
   * @param options the arrow IPC writer options.
   * @param outputFile where to write the file.
   * @return a table writer to use for writing out multiple tables.
   */
  public static TableWriter writeArrowIPCChunked(ArrowIPCWriterOptions options, File outputFile) {
    return new ArrowIPCTableWriter(options, outputFile);
  }

  /**
   * Get a table writer to write arrow IPC data and handle each chunk with a callback.
   * @param options the arrow IPC writer options.
   * @param consumer a class that will be called when host buffers are ready with arrow IPC
   *                 formatted data in them.
   * @return a table writer to use for writing out multiple tables.
   */
  public static TableWriter writeArrowIPCChunked(ArrowIPCWriterOptions options,
                                                 HostBufferConsumer consumer) {
    return new ArrowIPCTableWriter(options, consumer);
  }

  private static class ArrowReaderWrapper implements AutoCloseable {
    private HostBufferProvider provider;
    private HostMemoryBuffer buffer;

    private ArrowReaderWrapper(HostBufferProvider provider) {
      this.provider = provider;
      buffer = HostMemoryBuffer.allocate(10 * 1024 * 1024, false);
    }

    // Called From JNI
    public long readInto(long dstAddress, long amount) {
      long totalRead = 0;
      long amountLeft = amount;
      while (amountLeft > 0) {
        long amountToCopy = Math.min(amountLeft, buffer.length);
        long amountRead = provider.readInto(buffer, amountToCopy);
        buffer.copyToMemory(totalRead + dstAddress, amountRead);
        amountLeft -= amountRead;
        totalRead += amountRead;
        if (amountRead < amountToCopy) {
          // EOF
          amountLeft = 0;
        }
      }
      return totalRead;
    }

    @Override
    public void close()  {
      if (provider != null) {
        provider.close();
        provider = null;
      }

      if (buffer != null) {
        buffer.close();
        buffer = null;
      }
    }
  }

  private static class ArrowIPCStreamedTableReader implements StreamedTableReader {
    private final ArrowIPCOptions.NeedGpu callback;
    private long handle;
    private ArrowReaderWrapper provider;

    private ArrowIPCStreamedTableReader(ArrowIPCOptions options, File inputFile) {
      this.provider = null;
      this.handle = readArrowIPCFileBegin(
              inputFile.getAbsolutePath());
      this.callback = options.getCallback();
    }

    private ArrowIPCStreamedTableReader(ArrowIPCOptions options, HostBufferProvider provider) {
      this.provider = new ArrowReaderWrapper(provider);
      this.handle = readArrowIPCBufferBegin(this.provider);
      this.callback = options.getCallback();
    }

    @Override
    public Table getNextIfAvailable() throws CudfException {
      // In this case rowTarget is the minimum number of rows to read.
      return getNextIfAvailable(1);
    }

    @Override
    public Table getNextIfAvailable(int rowTarget) throws CudfException {
      long arrowTableHandle = readArrowIPCChunkToArrowTable(handle, rowTarget);
      try {
        if (arrowTableHandle == 0) {
          return null;
        }
        callback.needTheGpu();
        return new Table(convertArrowTableToCudf(arrowTableHandle));
      } finally {
        closeArrowTable(arrowTableHandle);
      }
    }

    @Override
    public void close() throws CudfException {
      if (handle != 0) {
        readArrowIPCEnd(handle);
      }
      handle = 0;
      if (provider != null) {
        provider.close();
        provider = null;
      }
    }
  }

  /**
   * Get a reader that will return tables.
   * @param options options for reading.
   * @param inputFile the file to read the Arrow IPC formatted data from
   * @return a reader.
   */
  public static StreamedTableReader readArrowIPCChunked(ArrowIPCOptions options, File inputFile) {
    return new ArrowIPCStreamedTableReader(options, inputFile);
  }

  /**
   * Get a reader that will return tables.
   * @param inputFile the file to read the Arrow IPC formatted data from
   * @return a reader.
   */
  public static StreamedTableReader readArrowIPCChunked(File inputFile) {
    return readArrowIPCChunked(ArrowIPCOptions.DEFAULT, inputFile);
  }

  /**
   * Get a reader that will return tables.
   * @param options options for reading.
   * @param provider what will provide the data being read.
   * @return a reader.
   */
  public static StreamedTableReader readArrowIPCChunked(ArrowIPCOptions options,
                                                        HostBufferProvider provider) {
    return new ArrowIPCStreamedTableReader(options, provider);
  }

  /**
   * Get a reader that will return tables.
   * @param provider what will provide the data being read.
   * @return a reader.
   */
  public static StreamedTableReader readArrowIPCChunked(HostBufferProvider provider) {
    return readArrowIPCChunked(ArrowIPCOptions.DEFAULT, provider);
  }

  /**
   * Concatenate multiple tables together to form a single table.
   * The schema of each table (i.e.: number of columns and types of each column) must be equal
   * across all tables and will determine the schema of the resulting table.
   */
  public static Table concatenate(Table... tables) {
    if (tables.length < 2) {
      throw new IllegalArgumentException("concatenate requires 2 or more tables");
    }
    int numColumns = tables[0].getNumberOfColumns();
    long[] tableHandles = new long[tables.length];
    for (int i = 0; i < tables.length; ++i) {
      tableHandles[i] = tables[i].nativeHandle;
      assert tables[i].getNumberOfColumns() == numColumns : "all tables must have the same schema";
    }
    return new Table(concatenate(tableHandles));
  }

  /**
   * Interleave all columns into a single column. Columns must all have the same data type and length.
   *
   * Example:
   * ```
   * input  = [[A1, A2, A3], [B1, B2, B3]]
   * return = [A1, B1, A2, B2, A3, B3]
   * ```
   *
   * @return The interleaved columns as a single column
   */
  public ColumnVector interleaveColumns() {
    assert this.getNumberOfColumns() >= 2 : ".interleaveColumns() operation requires at least 2 columns";
    return new ColumnVector(interleaveColumns(this.nativeHandle));
  }

  /**
   * Repeat each row of this table count times.
   * @param count the number of times to repeat each row.
   * @return the new Table.
   */
  public Table repeat(int count) {
    return new Table(repeatStaticCount(this.nativeHandle, count));
  }

  /**
   * Create a new table by repeating each row of this table. The number of
   * repetitions of each row is defined by the corresponding value in counts.
   * @param counts the number of times to repeat each row. Cannot have nulls, must be an
   *               Integer type, and must have one entry for each row in the table.
   * @return the new Table.
   * @throws CudfException on any error.
   */
  public Table repeat(ColumnView counts) {
    return repeat(counts, true);
  }

  /**
   * Create a new table by repeating each row of this table. The number of
   * repetitions of each row is defined by the corresponding value in counts.
   * @param counts the number of times to repeat each row. Cannot have nulls, must be an
   *               Integer type, and must have one entry for each row in the table.
   * @param checkCount should counts be checked for errors before processing. Be careful if you
   *                   disable this because if you pass in bad data you might just get back an
   *                   empty table or bad data.
   * @return the new Table.
   * @throws CudfException on any error.
   */
  public Table repeat(ColumnView counts, boolean checkCount) {
    return new Table(repeatColumnCount(this.nativeHandle, counts.getNativeView(), checkCount));
  }

  /**
   * Partition this table using the mapping in partitionMap. partitionMap must be an integer
   * column. The number of rows in partitionMap must be the same as this table.  Each row
   * in the map will indicate which partition the rows in the table belong to.
   * @param partitionMap the partitions for each row.
   * @param numberOfPartitions number of partitions
   * @return {@link PartitionedTable} Table that exposes a limited functionality of the
   * {@link Table} class
   */
  public PartitionedTable partition(ColumnView partitionMap, int numberOfPartitions) {
    int[] partitionOffsets = new int[numberOfPartitions];
    return new PartitionedTable(new Table(partition(
        getNativeView(),
        partitionMap.getNativeView(),
        partitionOffsets.length,
        partitionOffsets)), partitionOffsets);
  }

  /**
   * Find smallest indices in a sorted table where values should be inserted to maintain order.
   * <pre>
   * Example:
   *
   *  Single column:
   *      idx            0   1   2   3   4
   *   inputTable  =   { 10, 20, 20, 30, 50 }
   *   valuesTable =   { 20 }
   *   result      =   { 1 }
   *
   *  Multi Column:
   *      idx                0    1    2    3    4
   *   inputTable      = {{  10,  20,  20,  20,  20 },
   *                      { 5.0,  .5,  .5,  .7,  .7 },
   *                      {  90,  77,  78,  61,  61 }}
   *   valuesTable     = {{ 20 },
   *                      { .7 },
   *                      { 61 }}
   *   result          = {  3 }
   * </pre>
   * The input table and the values table need to be non-empty (row count > 0)
   * @param areNullsSmallest per column, true if nulls are assumed smallest
   * @param valueTable the table of values to find insertion locations for
   * @param descFlags per column indicates the ordering, true if descending.
   * @return ColumnVector with lower bound indices for all rows in valueTable
   */
  public ColumnVector lowerBound(boolean[] areNullsSmallest,
      Table valueTable, boolean[] descFlags) {
    assertForBounds(valueTable);
    return new ColumnVector(bound(this.nativeHandle, valueTable.nativeHandle,
      descFlags, areNullsSmallest, false));
  }

  /**
   * Find smallest indices in a sorted table where values should be inserted to maintain order.
   * This is a convenience method. It pulls out the columns indicated by the args and sets up the
   * ordering properly to call `lowerBound`.
   * @param valueTable the table of values to find insertion locations for
   * @param args the sort order used to sort this table.
   * @return ColumnVector with lower bound indices for all rows in valueTable
   */
  public ColumnVector lowerBound(Table valueTable, OrderByArg... args) {
    boolean[] areNullsSmallest = new boolean[args.length];
    boolean[] descFlags = new boolean[args.length];
    ColumnVector[] inputColumns = new ColumnVector[args.length];
    ColumnVector[] searchColumns = new ColumnVector[args.length];
    for (int i = 0; i < args.length; i++) {
      areNullsSmallest[i] = args[i].isNullSmallest;
      descFlags[i] = args[i].isDescending;
      inputColumns[i] = columns[args[i].index];
      searchColumns[i] = valueTable.columns[args[i].index];
    }
    try (Table input = new Table(inputColumns);
         Table search = new Table(searchColumns)) {
      return input.lowerBound(areNullsSmallest, search, descFlags);
    }
  }

  /**
   * Find largest indices in a sorted table where values should be inserted to maintain order.
   * Given a sorted table return the upper bound.
   * <pre>
   * Example:
   *
   *  Single column:
   *      idx            0   1   2   3   4
   *   inputTable  =   { 10, 20, 20, 30, 50 }
   *   valuesTable =   { 20 }
   *   result      =   { 3 }
   *
   *  Multi Column:
   *      idx                0    1    2    3    4
   *   inputTable      = {{  10,  20,  20,  20,  20 },
   *                      { 5.0,  .5,  .5,  .7,  .7 },
   *                      {  90,  77,  78,  61,  61 }}
   *   valuesTable     = {{ 20 },
   *                      { .7 },
   *                      { 61 }}
   *   result          = {  5 }
   * </pre>
   * The input table and the values table need to be non-empty (row count > 0)
   * @param areNullsSmallest per column, true if nulls are assumed smallest
   * @param valueTable the table of values to find insertion locations for
   * @param descFlags per column indicates the ordering, true if descending.
   * @return ColumnVector with upper bound indices for all rows in valueTable
   */
  public ColumnVector upperBound(boolean[] areNullsSmallest,
      Table valueTable, boolean[] descFlags) {
    assertForBounds(valueTable);
    return new ColumnVector(bound(this.nativeHandle, valueTable.nativeHandle,
      descFlags, areNullsSmallest, true));
  }

  /**
   * Find largest indices in a sorted table where values should be inserted to maintain order.
   * This is a convenience method. It pulls out the columns indicated by the args and sets up the
   * ordering properly to call `upperBound`.
   * @param valueTable the table of values to find insertion locations for
   * @param args the sort order used to sort this table.
   * @return ColumnVector with upper bound indices for all rows in valueTable
   */
  public ColumnVector upperBound(Table valueTable, OrderByArg... args) {
    boolean[] areNullsSmallest = new boolean[args.length];
    boolean[] descFlags = new boolean[args.length];
    ColumnVector[] inputColumns = new ColumnVector[args.length];
    ColumnVector[] searchColumns = new ColumnVector[args.length];
    for (int i = 0; i < args.length; i++) {
      areNullsSmallest[i] = args[i].isNullSmallest;
      descFlags[i] = args[i].isDescending;
      inputColumns[i] = columns[args[i].index];
      searchColumns[i] = valueTable.columns[args[i].index];
    }
    try (Table input = new Table(inputColumns);
         Table search = new Table(searchColumns)) {
      return input.upperBound(areNullsSmallest, search, descFlags);
    }
  }

  private void assertForBounds(Table valueTable) {
    assert this.getRowCount() != 0 : "Input table cannot be empty";
    assert valueTable.getRowCount() != 0 : "Value table cannot be empty";
    for (int i = 0; i < Math.min(columns.length, valueTable.columns.length); i++) {
      assert valueTable.columns[i].getType().equals(this.getColumn(i).getType()) :
          "Input and values tables' data types do not match";
    }
  }

  /**
   * Joins two tables all of the left against all of the right. Be careful as this
   * gets very big and you can easily use up all of the GPUs memory.
   * @param right the right table
   * @return the joined table.  The order of the columns returned will be left columns,
   * right columns.
   */
  public Table crossJoin(Table right) {
    return new Table(Table.crossJoin(this.nativeHandle, right.nativeHandle));
  }

  /////////////////////////////////////////////////////////////////////////////
  // TABLE MANIPULATION APIs
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Get back a gather map that can be used to sort the data. This allows you to sort by data
   * that does not appear in the final result and not pay the cost of gathering the data that
   * is only needed for sorting.
   * @param args what order to sort the data by
   * @return a gather map
   */
  public ColumnVector sortOrder(OrderByArg... args) {
    long[] sortKeys = new long[args.length];
    boolean[] isDescending = new boolean[args.length];
    boolean[] areNullsSmallest = new boolean[args.length];
    for (int i = 0; i < args.length; i++) {
      int index = args[i].index;
      assert (index >= 0 && index < columns.length) :
          "index is out of range 0 <= " + index + " < " + columns.length;
      isDescending[i] = args[i].isDescending;
      areNullsSmallest[i] = args[i].isNullSmallest;
      sortKeys[i] = columns[index].getNativeView();
    }

    return new ColumnVector(sortOrder(nativeHandle, sortKeys, isDescending, areNullsSmallest));
  }

  /**
   * Orders the table using the sortkeys returning a new allocated table. The caller is
   * responsible for cleaning up
   * the {@link ColumnVector} returned as part of the output {@link Table}
   * <p>
   * Example usage: orderBy(true, OrderByArg.asc(0), OrderByArg.desc(3)...);
   * @param args Suppliers to initialize sortKeys.
   * @return Sorted Table
   */
  public Table orderBy(OrderByArg... args) {
    long[] sortKeys = new long[args.length];
    boolean[] isDescending = new boolean[args.length];
    boolean[] areNullsSmallest = new boolean[args.length];
    for (int i = 0; i < args.length; i++) {
      int index = args[i].index;
      assert (index >= 0 && index < columns.length) :
          "index is out of range 0 <= " + index + " < " + columns.length;
      isDescending[i] = args[i].isDescending;
      areNullsSmallest[i] = args[i].isNullSmallest;
      sortKeys[i] = columns[index].getNativeView();
    }

    return new Table(orderBy(nativeHandle, sortKeys, isDescending, areNullsSmallest));
  }

  /**
   * Merge multiple already sorted tables keeping the sort order the same.
   * This is a more efficient version of concatenate followed by orderBy, but requires that
   * the input already be sorted.
   * @param tables the tables that should be merged.
   * @param args the ordering of the tables.  Should match how they were sorted
   *             initially.
   * @return a combined sorted table.
   */
  public static Table merge(Table[] tables, OrderByArg... args) {
    assert tables.length > 0;
    long[] tableHandles = new long[tables.length];
    Table first = tables[0];
    assert args.length <= first.columns.length;
    for (int i = 0; i < tables.length; i++) {
      Table t = tables[i];
      assert t != null;
      assert t.columns.length == first.columns.length;
      tableHandles[i] = t.nativeHandle;
    }
    int[] sortKeyIndexes = new int[args.length];
    boolean[] isDescending = new boolean[args.length];
    boolean[] areNullsSmallest = new boolean[args.length];
    for (int i = 0; i < args.length; i++) {
      int index = args[i].index;
      assert (index >= 0 && index < first.columns.length) :
          "index is out of range 0 <= " + index + " < " + first.columns.length;
      isDescending[i] = args[i].isDescending;
      areNullsSmallest[i] = args[i].isNullSmallest;
      sortKeyIndexes[i] = index;
    }

    return new Table(merge(tableHandles, sortKeyIndexes, isDescending, areNullsSmallest));
  }

  /**
   * Merge multiple already sorted tables keeping the sort order the same.
   * This is a more efficient version of concatenate followed by orderBy, but requires that
   * the input already be sorted.
   * @param tables the tables that should be merged.
   * @param args the ordering of the tables.  Should match how they were sorted
   *             initially.
   * @return a combined sorted table.
   */
  public static Table merge(List<Table> tables, OrderByArg... args) {
    return merge(tables.toArray(new Table[tables.size()]), args);
  }

  /**
   * Returns aggregate operations grouped by columns provided in indices
   * @param groupByOptions Options provided in the builder
   * @param indices columns to be considered for groupBy
   */
  public GroupByOperation groupBy(GroupByOptions groupByOptions, int... indices) {
    return groupByInternal(groupByOptions, indices);
  }

  /**
   * Returns aggregate operations grouped by columns provided in indices
   * with default options as below:
   *  - null is considered as key while grouping.
   *  - keys are not presorted.
   *  - empty key order array.
   *  - empty null order array.
   * @param indices columns to be considered for groupBy
   */
  public GroupByOperation groupBy(int... indices) {
    return groupByInternal(GroupByOptions.builder().withIgnoreNullKeys(false).build(),
        indices);
  }

  private GroupByOperation groupByInternal(GroupByOptions groupByOptions, int[] indices) {
    int[] operationIndicesArray = copyAndValidate(indices);
    return new GroupByOperation(this, groupByOptions, operationIndicesArray);
  }

  /**
   * Round-robin partition a table into the specified number of partitions. The first row is placed
   * in the specified starting partition, the next row is placed in the next partition, and so on.
   * When the last partition is reached then next partition is partition 0 and the algorithm
   * continues until all rows have been placed in partitions, evenly distributing the rows
   * among the partitions.
   * @param numberOfPartitions - number of partitions to use
   * @param startPartition - starting partition index (i.e.: where first row is placed).
   * @return - {@link PartitionedTable} - Table that exposes a limited functionality of the
   * {@link Table} class
   */
  public PartitionedTable roundRobinPartition(int numberOfPartitions, int startPartition) {
    int[] partitionOffsets = new int[numberOfPartitions];
    return new PartitionedTable(new Table(Table.roundRobinPartition(nativeHandle,
        numberOfPartitions, startPartition,
        partitionOffsets)), partitionOffsets);
  }

  public TableOperation onColumns(int... indices) {
    int[] operationIndicesArray = copyAndValidate(indices);
    return new TableOperation(this, operationIndicesArray);
  }

  private int[] copyAndValidate(int[] indices) {
    int[] operationIndicesArray = new int[indices.length];
    for (int i = 0; i < indices.length; i++) {
      operationIndicesArray[i] = indices[i];
      assert operationIndicesArray[i] >= 0 && operationIndicesArray[i] < columns.length :
          "operation index is out of range 0 <= " + operationIndicesArray[i] + " < " + columns.length;
    }
    return operationIndicesArray;
  }

  /**
   * Filters this table using a column of boolean values as a mask, returning a new one.
   * <p>
   * Given a mask column, each element `i` from the input columns
   * is copied to the output columns if the corresponding element `i` in the mask is
   * non-null and `true`. This operation is stable: the input order is preserved.
   * <p>
   * This table and mask columns must have the same number of rows.
   * <p>
   * The output table has size equal to the number of elements in boolean_mask
   * that are both non-null and `true`.
   * <p>
   * If the original table row count is zero, there is no error, and an empty table is returned.
   * @param mask column of type {@link DType#BOOL8} used as a mask to filter
   *             the input column
   * @return table containing copy of all elements of this table passing
   * the filter defined by the boolean mask
   */
  public Table filter(ColumnView mask) {
    assert mask.getType().equals(DType.BOOL8) : "Mask column must be of type BOOL8";
    assert getRowCount() == 0 || getRowCount() == mask.getRowCount() : "Mask column has incorrect size";
    return new Table(filter(nativeHandle, mask.getNativeView()));
  }

  /**
   * Split a table at given boundaries, but the result of each split has memory that is laid out
   * in a contiguous range of memory.  This allows for us to optimize copying the data in a single
   * operation.
   *
   * <code>
   * Example:
   * input:   [{10, 12, 14, 16, 18, 20, 22, 24, 26, 28},
   *           {50, 52, 54, 56, 58, 60, 62, 64, 66, 68}]
   * splits:  {2, 5, 9}
   * output:  [{{10, 12}, {14, 16, 18}, {20, 22, 24, 26}, {28}},
   *           {{50, 52}, {54, 56, 58}, {60, 62, 64, 66}, {68}}]
   * </code>
   * @param indices A vector of indices where to make the split
   * @return The tables split at those points. NOTE: It is the responsibility of the caller to
   * close the result. Each table and column holds a reference to the original buffer. But both
   * the buffer and the table must be closed for the memory to be released.
   */
  public ContiguousTable[] contiguousSplit(int... indices) {
    return contiguousSplit(nativeHandle, indices);
  }

  /**
   * Explodes a list column's elements.
   *
   * Any list is exploded, which means the elements of the list in each row are expanded
   * into new rows in the output. The corresponding rows for other columns in the input
   * are duplicated.
   *
   * <code>
   * Example:
   * input:  [[5,10,15], 100],
   *         [[20,25],   200],
   *         [[30],      300]
   * index: 0
   * output: [5,         100],
   *         [10,        100],
   *         [15,        100],
   *         [20,        200],
   *         [25,        200],
   *         [30,        300]
   * </code>
   *
   * Nulls propagate in different ways depending on what is null.
   * <code>
   * input:  [[5,null,15], 100],
   *         [null,        200]
   * index: 0
   * output: [5,           100],
   *         [null,        100],
   *         [15,          100]
   * </code>
   * Note that null lists are completely removed from the output
   * and nulls inside lists are pulled out and remain.
   *
   * @param index Column index to explode inside the table.
   * @return A new table with explode_col exploded.
   */
  public Table explode(int index) {
    assert 0 <= index && index < columns.length : "Column index is out of range";
    assert columns[index].getType().equals(DType.LIST) : "Column to explode must be of type LIST";
    return new Table(explode(nativeHandle, index));
  }

  /**
   * Explodes a list column's elements and includes a position column.
   *
   * Any list is exploded, which means the elements of the list in each row are expanded into new rows
   * in the output. The corresponding rows for other columns in the input are duplicated. A position
   * column is added that has the index inside the original list for each row. Example:
   * <code>
   * input:  [[5,10,15], 100],
   *         [[20,25],   200],
   *         [[30],      300]
   * index: 0
   * output: [0,   5,    100],
   *         [1,   10,   100],
   *         [2,   15,   100],
   *         [0,   20,   200],
   *         [1,   25,   200],
   *         [0,   30,   300]
   * </code>
   *
   * Nulls and empty lists propagate in different ways depending on what is null or empty.
   * <code>
   * input:  [[5,null,15], 100],
   *         [null,        200]
   * index: 0
   * output: [5,           100],
   *         [null,        100],
   *         [15,          100]
   * </code>
   *
   * Note that null lists are not included in the resulting table, but nulls inside
   * lists and empty lists will be represented with a null entry for that column in that row.
   *
   * @param index Column index to explode inside the table.
   * @return A new table with exploded value and position. The column order of return table is
   *         [cols before explode_input, explode_position, explode_value, cols after explode_input].
   */
  public Table explodePosition(int index) {
    assert 0 <= index && index < columns.length : "Column index is out of range";
    assert columns[index].getType().equals(DType.LIST) : "Column to explode must be of type LIST";
    return new Table(explodePosition(nativeHandle, index));
  }

  /**
   * Explodes a list column's elements.
   *
   * Any list is exploded, which means the elements of the list in each row are expanded
   * into new rows in the output. The corresponding rows for other columns in the input
   * are duplicated.
   *
   * <code>
   * Example:
   * input:  [[5,10,15], 100],
   *         [[20,25],   200],
   *         [[30],      300],
   * index: 0
   * output: [5,         100],
   *         [10,        100],
   *         [15,        100],
   *         [20,        200],
   *         [25,        200],
   *         [30,        300]
   * </code>
   *
   * Nulls propagate in different ways depending on what is null.
   * <code>
   *  input:  [[5,null,15], 100],
   *          [null,        200]
   * index: 0
   * output:  [5,           100],
   *          [null,        100],
   *          [15,          100],
   *          [null,        200]
   * </code>
   * Note that null lists are completely removed from the output
   * and nulls inside lists are pulled out and remain.
   *
   * @param index Column index to explode inside the table.
   * @return A new table with explode_col exploded.
   */
  public Table explodeOuter(int index) {
    assert 0 <= index && index < columns.length : "Column index is out of range";
    assert columns[index].getType().equals(DType.LIST) : "Column to explode must be of type LIST";
    return new Table(explodeOuter(nativeHandle, index));
  }

  /**
   * Explodes a list column's elements retaining any null entries or empty lists and includes a
   * position column.
   *
   * Any list is exploded, which means the elements of the list in each row are expanded into new rows
   * in the output. The corresponding rows for other columns in the input are duplicated. A position
   * column is added that has the index inside the original list for each row. Example:
   *
   * <code>
   * Example:
   * input:  [[5,10,15], 100],
   *         [[20,25],   200],
   *         [[30],      300],
   * index: 0
   * output: [0,   5,    100],
   *         [1,   10,   100],
   *         [2,   15,   100],
   *         [0,   20,   200],
   *         [1,   25,   200],
   *         [0,   30,   300]
   * </code>
   *
   * Nulls and empty lists propagate as null entries in the result.
   * <code>
   * input:  [[5,null,15], 100],
   *         [null,        200],
   *         [[],          300]
   * index: 0
   * output: [0,     5,    100],
   *         [1,  null,    100],
   *         [2,    15,    100],
   *         [0,  null,    200],
   *         [0,  null,    300]
   * </code>
   *
   *    returns
   *
   * @param index Column index to explode inside the table.
   * @return A new table with exploded value and position. The column order of return table is
   *         [cols before explode_input, explode_position, explode_value, cols after explode_input].
   */
  public Table explodeOuterPosition(int index) {
    assert 0 <= index && index < columns.length : "Column index is out of range";
    assert columns[index].getType().equals(DType.LIST) : "Column to explode must be of type LIST";
    return new Table(explodeOuterPosition(nativeHandle, index));
  }

  /**
   * Returns an approximate cumulative size in bits of all columns in the `table_view` for each row.
   * This function counts bits instead of bytes to account for the null mask which only has one
   * bit per row. Each row in the returned column is the sum of the per-row bit size for each column
   * in the table.
   *
   * In some cases, this is an inexact approximation. Specifically, columns of lists and strings
   * require N+1 offsets to represent N rows. It is up to the caller to calculate the small
   * additional overhead of the terminating offset for any group of rows being considered.
   *
   * This function returns the per-row bit sizes as the columns are currently formed. This can
   * end up being larger than the number you would get by gathering the rows. Specifically,
   * the push-down of struct column validity masks can nullify rows that contain data for
   * string or list columns. In these cases, the size returned is conservative such that:
   * row_bit_count(column(x)) >= row_bit_count(gather(column(x)))
   *
   * @return INT32 column of bit size per row of the table
   */
  public ColumnVector rowBitCount() {
    return new ColumnVector(rowBitCount(getNativeView()));
  }

  /**
   * Gathers the rows of this table according to `gatherMap` such that row "i"
   * in the resulting table's columns will contain row "gatherMap[i]" from this table.
   * The number of rows in the result table will be equal to the number of elements in
   * `gatherMap`.
   *
   * A negative value `i` in the `gatherMap` is interpreted as `i+n`, where
   * `n` is the number of rows in this table.

   * @param gatherMap the map of indexes.  Must be non-nullable and integral type.
   * @return the resulting Table.
   */
  public Table gather(ColumnView gatherMap) {
    return gather(gatherMap, true);
  }

  /**
   * Gathers the rows of this table according to `gatherMap` such that row "i"
   * in the resulting table's columns will contain row "gatherMap[i]" from this table.
   * The number of rows in the result table will be equal to the number of elements in
   * `gatherMap`.
   *
   * A negative value `i` in the `gatherMap` is interpreted as `i+n`, where
   * `n` is the number of rows in this table.

   * @param gatherMap the map of indexes.  Must be non-nullable and integral type.
   * @param checkBounds if true bounds checking is performed on the value. Be very careful
   *                    when setting this to false.
   * @return the resulting Table.
   */
  public Table gather(ColumnView gatherMap, boolean checkBounds) {
    return new Table(gather(nativeHandle, gatherMap.getNativeView(), checkBounds));
  }

  private GatherMap[] buildJoinGatherMaps(long[] gatherMapData) {
    long bufferSize = gatherMapData[0];
    long leftAddr = gatherMapData[1];
    long leftHandle = gatherMapData[2];
    long rightAddr = gatherMapData[3];
    long rightHandle = gatherMapData[4];
    GatherMap[] maps = new GatherMap[2];
    maps[0] = new GatherMap(DeviceMemoryBuffer.fromRmm(leftAddr, bufferSize, leftHandle));
    maps[1] = new GatherMap(DeviceMemoryBuffer.fromRmm(rightAddr, bufferSize, rightHandle));
    return maps;
  }

  /**
   * Computes the gather maps that can be used to manifest the result of a left equi-join between
   * two tables. It is assumed this table instance holds the key columns from the left table, and
   * the table argument represents the key columns from the right table. Two {@link GatherMap}
   * instances will be returned that can be used to gather the left and right tables,
   * respectively, to produce the result of the left join.
   * It is the responsibility of the caller to close the resulting gather map instances.
   * @param rightKeys join key columns from the right table
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left and right table gather maps
   */
  public GatherMap[] leftJoinGatherMaps(Table rightKeys, boolean compareNullsEqual) {
    if (getNumberOfColumns() != rightKeys.getNumberOfColumns()) {
      throw new IllegalArgumentException("column count mismatch, this: " + getNumberOfColumns() +
          "rightKeys: " + rightKeys.getNumberOfColumns());
    }
    long[] gatherMapData =
        leftJoinGatherMaps(getNativeView(), rightKeys.getNativeView(), compareNullsEqual);
    return buildJoinGatherMaps(gatherMapData);
  }

  /**
   * Computes the gather maps that can be used to manifest the result of a left join between
   * two tables when a conditional expression is true. It is assumed this table instance holds
   * the columns from the left table, and the table argument represents the columns from the
   * right table. Two {@link GatherMap} instances will be returned that can be used to gather
   * the left and right tables, respectively, to produce the result of the left join.
   * It is the responsibility of the caller to close the resulting gather map instances.
   * @param rightTable the right side table of the join in the join
   * @param condition conditional expression to evaluate during the join
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left and right table gather maps
   */
  public GatherMap[] leftJoinGatherMaps(Table rightTable, CompiledExpression condition,
                                        boolean compareNullsEqual) {
    long[] gatherMapData =
        conditionalLeftJoinGatherMaps(getNativeView(), rightTable.getNativeView(),
            condition.getNativeHandle(), compareNullsEqual);
    return buildJoinGatherMaps(gatherMapData);
  }

  /**
   * Computes the gather maps that can be used to manifest the result of an inner equi-join between
   * two tables. It is assumed this table instance holds the key columns from the left table, and
   * the table argument represents the key columns from the right table. Two {@link GatherMap}
   * instances will be returned that can be used to gather the left and right tables,
   * respectively, to produce the result of the inner join.
   * It is the responsibility of the caller to close the resulting gather map instances.
   * @param rightKeys join key columns from the right table
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left and right table gather maps
   */
  public GatherMap[] innerJoinGatherMaps(Table rightKeys, boolean compareNullsEqual) {
    if (getNumberOfColumns() != rightKeys.getNumberOfColumns()) {
      throw new IllegalArgumentException("column count mismatch, this: " + getNumberOfColumns() +
          "rightKeys: " + rightKeys.getNumberOfColumns());
    }
    long[] gatherMapData =
        innerJoinGatherMaps(getNativeView(), rightKeys.getNativeView(), compareNullsEqual);
    return buildJoinGatherMaps(gatherMapData);
  }

  /**
   * Computes the gather maps that can be used to manifest the result of an inner join between
   * two tables when a conditional expression is true. It is assumed this table instance holds
   * the columns from the left table, and the table argument represents the columns from the
   * right table. Two {@link GatherMap} instances will be returned that can be used to gather
   * the left and right tables, respectively, to produce the result of the inner join.
   * It is the responsibility of the caller to close the resulting gather map instances.
   * @param rightTable the right side table of the join
   * @param condition conditional expression to evaluate during the join
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left and right table gather maps
   */
  public GatherMap[] innerJoinGatherMaps(Table rightTable, CompiledExpression condition,
                                         boolean compareNullsEqual) {
    long[] gatherMapData =
        conditionalInnerJoinGatherMaps(getNativeView(), rightTable.getNativeView(),
            condition.getNativeHandle(), compareNullsEqual);
    return buildJoinGatherMaps(gatherMapData);
  }

  /**
   * Computes the gather maps that can be used to manifest the result of an full equi-join between
   * two tables. It is assumed this table instance holds the key columns from the left table, and
   * the table argument represents the key columns from the right table. Two {@link GatherMap}
   * instances will be returned that can be used to gather the left and right tables,
   * respectively, to produce the result of the full join.
   * It is the responsibility of the caller to close the resulting gather map instances.
   * @param rightKeys join key columns from the right table
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left and right table gather maps
   */
  public GatherMap[] fullJoinGatherMaps(Table rightKeys, boolean compareNullsEqual) {
    if (getNumberOfColumns() != rightKeys.getNumberOfColumns()) {
      throw new IllegalArgumentException("column count mismatch, this: " + getNumberOfColumns() +
          "rightKeys: " + rightKeys.getNumberOfColumns());
    }
    long[] gatherMapData =
        fullJoinGatherMaps(getNativeView(), rightKeys.getNativeView(), compareNullsEqual);
    return buildJoinGatherMaps(gatherMapData);
  }

  /**
   * Computes the gather maps that can be used to manifest the result of a full join between
   * two tables when a conditional expression is true. It is assumed this table instance holds
   * the columns from the left table, and the table argument represents the columns from the
   * right table. Two {@link GatherMap} instances will be returned that can be used to gather
   * the left and right tables, respectively, to produce the result of the full join.
   * It is the responsibility of the caller to close the resulting gather map instances.
   * @param rightTable the right side table of the join
   * @param condition conditional expression to evaluate during the join
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left and right table gather maps
   */
  public GatherMap[] fullJoinGatherMaps(Table rightTable, CompiledExpression condition,
                                         boolean compareNullsEqual) {
    long[] gatherMapData =
        conditionalFullJoinGatherMaps(getNativeView(), rightTable.getNativeView(),
            condition.getNativeHandle(), compareNullsEqual);
    return buildJoinGatherMaps(gatherMapData);
  }

  private GatherMap buildSemiJoinGatherMap(long[] gatherMapData) {
    long bufferSize = gatherMapData[0];
    long leftAddr = gatherMapData[1];
    long leftHandle = gatherMapData[2];
    return new GatherMap(DeviceMemoryBuffer.fromRmm(leftAddr, bufferSize, leftHandle));
  }

  /**
   * Computes the gather map that can be used to manifest the result of a left semi-join between
   * two tables. It is assumed this table instance holds the key columns from the left table, and
   * the table argument represents the key columns from the right table. The {@link GatherMap}
   * instance returned can be used to gather the left table to produce the result of the
   * left semi-join.
   * It is the responsibility of the caller to close the resulting gather map instance.
   * @param rightKeys join key columns from the right table
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left table gather map
   */
  public GatherMap leftSemiJoinGatherMap(Table rightKeys, boolean compareNullsEqual) {
    if (getNumberOfColumns() != rightKeys.getNumberOfColumns()) {
      throw new IllegalArgumentException("column count mismatch, this: " + getNumberOfColumns() +
          "rightKeys: " + rightKeys.getNumberOfColumns());
    }
    long[] gatherMapData =
        leftSemiJoinGatherMap(getNativeView(), rightKeys.getNativeView(), compareNullsEqual);
    return buildSemiJoinGatherMap(gatherMapData);
  }

  /**
   * Computes the gather map that can be used to manifest the result of a left semi join between
   * two tables when a conditional expression is true. It is assumed this table instance holds
   * the columns from the left table, and the table argument represents the columns from the
   * right table. The {@link GatherMap} instance returned can be used to gather the left table
   * to produce the result of the left semi join.
   * It is the responsibility of the caller to close the resulting gather map instance.
   * @param rightTable the right side table of the join
   * @param condition conditional expression to evaluate during the join
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left table gather map
   */
  public GatherMap leftSemiJoinGatherMap(Table rightTable, CompiledExpression condition,
                                         boolean compareNullsEqual) {
    long[] gatherMapData =
        conditionalLeftSemiJoinGatherMap(getNativeView(), rightTable.getNativeView(),
            condition.getNativeHandle(), compareNullsEqual);
    return buildSemiJoinGatherMap(gatherMapData);
  }

  /**
   * Computes the gather map that can be used to manifest the result of a left anti-join between
   * two tables. It is assumed this table instance holds the key columns from the left table, and
   * the table argument represents the key columns from the right table. The {@link GatherMap}
   * instance returned can be used to gather the left table to produce the result of the
   * left anti-join.
   * It is the responsibility of the caller to close the resulting gather map instance.
   * @param rightKeys join key columns from the right table
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left table gather map
   */
  public GatherMap leftAntiJoinGatherMap(Table rightKeys, boolean compareNullsEqual) {
    if (getNumberOfColumns() != rightKeys.getNumberOfColumns()) {
      throw new IllegalArgumentException("column count mismatch, this: " + getNumberOfColumns() +
          "rightKeys: " + rightKeys.getNumberOfColumns());
    }
    long[] gatherMapData =
        leftAntiJoinGatherMap(getNativeView(), rightKeys.getNativeView(), compareNullsEqual);
    return buildSemiJoinGatherMap(gatherMapData);
  }

  /**
   * Computes the gather map that can be used to manifest the result of a left anti join between
   * two tables when a conditional expression is true. It is assumed this table instance holds
   * the columns from the left table, and the table argument represents the columns from the
   * right table. The {@link GatherMap} instance returned can be used to gather the left table
   * to produce the result of the left anti join.
   * It is the responsibility of the caller to close the resulting gather map instance.
   * @param rightTable the right side table of the join
   * @param condition conditional expression to evaluate during the join
   * @param compareNullsEqual true if null key values should match otherwise false
   * @return left table gather map
   */
  public GatherMap leftAntiJoinGatherMap(Table rightTable, CompiledExpression condition,
                                         boolean compareNullsEqual) {
    long[] gatherMapData =
        conditionalLeftAntiJoinGatherMap(getNativeView(), rightTable.getNativeView(),
            condition.getNativeHandle(), compareNullsEqual);
    return buildSemiJoinGatherMap(gatherMapData);
  }

  /**
   * Convert this table of columns into a row major format that is useful for interacting with other
   * systems that do row major processing of the data. Currently only fixed-width column types are
   * supported.
   * <p/>
   * The output is one or more ColumnVectors that are lists of bytes. A ColumnVector that is a
   * list of bytes can have at most 2GB of data stored in it. Multiple ColumnVectors are returned
   * if not all of the data can fit in a single one.
   * <p/>
   * Each row in the returned ColumnVector array corresponds to a row in the input table. The rows
   * will be in the same order as the input Table. The first ColumnVector in the array will hold
   * the first N rows followed by the second ColumnVector and so on.  The following illustrates
   * this and also shows some of the internal structure that will be explained later.
   * <p/><pre>
   * result[0]:
   *  | row 0 | validity for row 0 | padding |
   *  ...
   *  | row N | validity for row N | padding |
   *  result[1]:
   *  |row N+1 | validity for row N+1 | padding |
   *  ...
   * </pre>
   *
   * The format of each row is similar in layout to a C struct where each column will have padding
   * in front of it to align it properly. Each row has padding inserted at the end so the next row
   * is aligned to a 64-bit boundary. This is so that the first column will always start at the
   * beginning (first byte) of the list of bytes and each row has a consistent layout for fixed
   * width types.
   * <p/>
   * Validity bytes are added to the end of the row. There will be one byte for each 8 columns in a
   * row. Because the validity is byte aligned there is no padding between it and the last column
   * in the row.
   * <p/>
   * For example a table consisting of the following columns A, B, C with the corresponding types
   * <p/><pre>
   *   | A - BOOL8 (8-bit) | B - INT16 (16-bit) | C - DURATION_DAYS (32-bit) |
   * </pre>
   * <p/>
   *  Will have a layout that looks like
   *  <p/><pre>
   *  | A_0 | P | B_0 | B_1 | C_0 | C_1 | C_2 | C_3 | V0 | P | P | P | P | P | P | P |
   * </pre>
   * <p/>
   * In this P corresponds to a byte of padding, [LETTER]_[NUMBER] represents the NUMBER
   * byte of the corresponding LETTER column, and V[NUMBER] is a validity byte for the `NUMBER * 8`
   * to `(NUMBER + 1) * 8` columns.
   * <p/>
   * The order of the columns will not be changed, but to reduce the total amount of padding it is
   * recommended to order the columns in the following way.
   * <p/>
   * <ol>
   *  <li>64-bit columns</li>
   *  <li>32-bit columns</li>
   *  <li>16-bit columns</li>
   *  <li>8-bit columns</li>
   * </ol>
   * <p/>
   * This way padding is only inserted at the end of a row to make the next column 64-bit aligned.
   * So for the example above if the columns were ordered C, B, A the layout would be.
   * <pre>
   * | C_0 | C_1 | C_2 | C_3 | B_0 | B_1 | A_0 | V0 |
   * </pre>
   * This would have reduced the overall size of the data transferred by half.
   * <p/>
   * One of the main motivations for doing a row conversion on the GPU is to avoid cache problems
   * when walking through columnar data on the CPU in a row wise manner. If you are not transferring
   * very many columns it is likely to be more efficient to just pull back the columns and walk
   * through them. This is especially true of a single column of fixed width data. The extra
   * padding will slow down the transfer and looking at only a handful of buffers is not likely to
   * cause cache issues.
   * <p/>
   * There are some limits on the size of a single row.  If the row is larger than 1KB this will
   * throw an exception.
   */
  public ColumnVector[] convertToRows() {
    long[] ptrs = convertToRows(nativeHandle);
    ColumnVector[] ret = new ColumnVector[ptrs.length];
    for (int i = 0; i < ptrs.length; i++) {
      ret[i] = new ColumnVector(ptrs[i]);
    }
    return ret;
  }

  /**
   * Convert a column of list of bytes that is formatted like the output from `convertToRows`
   * and convert it back to a table.
   * @param vec the row data to process.
   * @param schema the types of each column.
   * @return the parsed table.
   */
  public static Table convertFromRows(ColumnView vec, DType ... schema) {
    // TODO at some point we need a schema that support nesting so we can support nested types
    // TODO we will need scale at some point very soon too
    int[] types = new int[schema.length];
    int[] scale = new int[schema.length];
    for (int i = 0; i < schema.length; i++) {
      types[i] = schema[i].typeId.nativeId;
      scale[i] = schema[i].getScale();

    }
    return new Table(convertFromRows(vec.getNativeView(), types, scale));
  }

  /**
   * Construct a table from a packed representation.
   * @param metadata host-based metadata for the table
   * @param data GPU data buffer for the table
   * @return table which is zero-copy reconstructed from the packed-form
   */
  public static Table fromPackedTable(ByteBuffer metadata, DeviceMemoryBuffer data) {
    // Ensure the metadata buffer is direct so it can be passed to JNI
    ByteBuffer directBuffer = metadata;
    if (!directBuffer.isDirect()) {
      directBuffer = ByteBuffer.allocateDirect(metadata.remaining());
      directBuffer.put(metadata);
      directBuffer.flip();
    }

    long[] columnViewAddresses = columnViewsFromPacked(directBuffer, data.getAddress());
    ColumnVector[] columns = new ColumnVector[columnViewAddresses.length];
    Table result = null;
    try {
      for (int i = 0; i < columns.length; i++) {
        columns[i] = ColumnVector.fromViewWithContiguousAllocation(columnViewAddresses[i], data);
        columnViewAddresses[i] = 0;
      }
      result = new Table(columns);
    } catch (Throwable t) {
      for (int i = 0; i < columns.length; i++) {
        if (columns[i] != null) {
          columns[i].close();
        }
        if (columnViewAddresses[i] != 0) {
          ColumnView.deleteColumnView(columnViewAddresses[i]);
        }
      }
      throw t;
    }

    // close columns to leave the resulting table responsible for freeing underlying columns
    for (ColumnVector column : columns) {
      column.close();
    }

    return result;
  }

  /////////////////////////////////////////////////////////////////////////////
  // HELPER CLASSES
  /////////////////////////////////////////////////////////////////////////////

  /**
   * class to encapsulate indices and table
   */
  private final static class Operation {
    final int[] indices;
    final Table table;

    Operation(Table table, int... indices) {
      this.indices = indices;
      this.table = table;
    }
  }

  /**
   * Internal class used to keep track of operations on a given column.
   */
  private static final class ColumnOps {
    private final HashMap<Aggregation, List<Integer>> ops = new HashMap<>();

    /**
     * Add an operation on a given column
     * @param op the operation
     * @param index the column index the operation is on.
     * @return 1 if it was not a duplicate or 0 if it was a duplicate.  This is mostly for
     * bookkeeping so we can easily allocate the correct data size later on.
     */
    public int add(Aggregation op, int index) {
      int ret = 0;
      List<Integer> indexes = ops.get(op);
      if (indexes == null) {
        ret++;
        indexes = new ArrayList<>();
        ops.put(op, indexes);
      }
      indexes.add(index);
      return ret;
    }

    public Set<Aggregation> operations() {
      return ops.keySet();
    }

    public Collection<List<Integer>> outputIndices() {
      return ops.values();
    }
  }

  /**
   * Internal class used to keep track of operations on a given column.
   */
  private static final class ColumnWindowOps {
    // Map AggOp -> Output column index.
    private final HashMap<AggregationOverWindow, List<Integer>> ops = new HashMap<>();

    public int add(AggregationOverWindow op, int index) {
      int ret = 0;
      List<Integer> indexes = ops.get(op);
      if (indexes == null) {
        ret++;
        indexes = new ArrayList<>();
        ops.put(op, indexes);
      }
      indexes.add(index);
      return ret;
    }

    public Set<AggregationOverWindow> operations() {
      return ops.keySet();
    }

    public Collection<List<Integer>> outputIndices() {
      return ops.values();
    }
  }

  /**
   * Class representing groupby operations
   */
  public static final class GroupByOperation {

    private final Operation operation;
    private final GroupByOptions groupByOptions;

    GroupByOperation(final Table table, GroupByOptions groupByOptions, final int... indices) {
      operation = new Operation(table, indices);
      this.groupByOptions = groupByOptions;
    }

    /**
     * Aggregates the group of columns represented by indices
     * Usage:
     *      aggregate(count(), max(2),...);
     *      example:
     *        input : 1, 1, 1
     *                1, 2, 1
     *                2, 4, 5
     *
     *        table.groupBy(0, 2).count()
     *
     *                col0, col1
     *        output:   1,   1
     *                  1,   2
     *                  2,   1 ==> aggregated count
     */
    public Table aggregate(GroupByAggregationOnColumn... aggregates) {
      assert aggregates != null;

      // To improve performance and memory we want to remove duplicate operations
      // and also group the operations by column so hopefully cudf can do multiple aggregations
      // in a single pass.

      // Use a tree map to make debugging simpler (columns are all in the same order)
      TreeMap<Integer, ColumnOps> groupedOps = new TreeMap<>();
      // Total number of operations that will need to be done.
      int keysLength = operation.indices.length;
      int totalOps = 0;
      for (int outputIndex = 0; outputIndex < aggregates.length; outputIndex++) {
        GroupByAggregationOnColumn agg = aggregates[outputIndex];
        ColumnOps ops = groupedOps.computeIfAbsent(agg.getColumnIndex(), (idx) -> new ColumnOps());
        totalOps += ops.add(agg.getWrapped().getWrapped(), outputIndex + keysLength);
      }
      int[] aggColumnIndexes = new int[totalOps];
      long[] aggOperationInstances = new long[totalOps];
      try {
        int opIndex = 0;
        for (Map.Entry<Integer, ColumnOps> entry: groupedOps.entrySet()) {
          int columnIndex = entry.getKey();
          for (Aggregation operation: entry.getValue().operations()) {
            aggColumnIndexes[opIndex] = columnIndex;
            aggOperationInstances[opIndex] = operation.createNativeInstance();
            opIndex++;
          }
        }
        assert opIndex == totalOps : opIndex + " == " + totalOps;

        try (Table aggregate = new Table(groupByAggregate(
            operation.table.nativeHandle,
            operation.indices,
            aggColumnIndexes,
            aggOperationInstances,
            groupByOptions.getIgnoreNullKeys(),
            groupByOptions.getKeySorted(),
            groupByOptions.getKeysDescending(),
            groupByOptions.getKeysNullSmallest()))) {
          // prepare the final table
          ColumnVector[] finalCols = new ColumnVector[keysLength + aggregates.length];

          // get the key columns
          for (int aggIndex = 0; aggIndex < keysLength; aggIndex++) {
            finalCols[aggIndex] = aggregate.getColumn(aggIndex);
          }

          int inputColumn = keysLength;
          // Now get the aggregation columns
          for (ColumnOps ops: groupedOps.values()) {
            for (List<Integer> indices: ops.outputIndices()) {
              for (int outIndex: indices) {
                finalCols[outIndex] = aggregate.getColumn(inputColumn);
              }
              inputColumn++;
            }
          }
          return new Table(finalCols);
        }
      } finally {
        Aggregation.close(aggOperationInstances);
      }
    }

    /**
     * Computes row-based window aggregation functions on the Table/projection, 
     * based on windows specified in the argument.
     * 
     * This method enables queries such as the following SQL:
     * 
     *  SELECT user_id, 
     *         MAX(sales_amt) OVER(PARTITION BY user_id ORDER BY date 
     *                             ROWS BETWEEN 1 PRECEDING and 1 FOLLOWING)
     *  FROM my_sales_table WHERE ...
     * 
     * Each window-aggregation is represented by a different {@link AggregationOverWindow} argument,
     * indicating:
     *  1. the {@link Aggregation.Kind},
     *  2. the number of rows preceding and following the current row, within a window,
     *  3. the minimum number of observations within the defined window
     * 
     * This method returns a {@link Table} instance, with one result column for each specified
     * window aggregation.
     * 
     * In this example, for the following input:
     * 
     *  [ // user_id,  sales_amt
     *    { "user1",     10      },
     *    { "user2",     20      },
     *    { "user1",     20      },
     *    { "user1",     10      },
     *    { "user2",     30      },
     *    { "user2",     80      },
     *    { "user1",     50      },
     *    { "user1",     60      },
     *    { "user2",     40      }
     *  ]
     * 
     * Partitioning (grouping) by `user_id` yields the following `sales_amt` vector 
     * (with 2 groups, one for each distinct `user_id`):
     * 
     *    [ 10,  20,  10,  50,  60,  20,  30,  80,  40 ]
     *      <-------user1-------->|<------user2------->
     * 
     * The SUM aggregation is applied with 1 preceding and 1 following
     * row, with a minimum of 1 period. The aggregation window is thus 3 rows wide,
     * yielding the following column:
     * 
     *    [ 30, 40,  80, 120, 110,  50, 130, 150, 120 ]
     * 
     * @param windowAggregates the window-aggregations to be performed
     * @return Table instance, with each column containing the result of each aggregation.
     * @throws IllegalArgumentException if the window arguments are not of type
     * {@link WindowOptions.FrameType#ROWS},
     * i.e. a timestamp column is specified for a window-aggregation.
     */
    public Table aggregateWindows(AggregationOverWindow... windowAggregates) {
      // To improve performance and memory we want to remove duplicate operations
      // and also group the operations by column so hopefully cudf can do multiple aggregations
      // in a single pass.

      // Use a tree map to make debugging simpler (columns are all in the same order)
      TreeMap<Integer, ColumnWindowOps> groupedOps = new TreeMap<>(); // Map agg-col-id -> Agg ColOp.
      // Total number of operations that will need to be done.
      int totalOps = 0;
      for (int outputIndex = 0; outputIndex < windowAggregates.length; outputIndex++) {
        AggregationOverWindow agg = windowAggregates[outputIndex];
        if (agg.getWindowOptions().getFrameType() != WindowOptions.FrameType.ROWS) {
          throw new IllegalArgumentException("Expected ROWS-based window specification. Unexpected window type: " 
                  + agg.getWindowOptions().getFrameType());
        }
        ColumnWindowOps ops = groupedOps.computeIfAbsent(agg.getColumnIndex(), (idx) -> new ColumnWindowOps());
        totalOps += ops.add(agg, outputIndex);
      }

      int[] aggColumnIndexes = new int[totalOps];
      long[] aggInstances = new long[totalOps];
      try {
        int[] aggPrecedingWindows = new int[totalOps];
        int[] aggFollowingWindows = new int[totalOps];
        int[] aggMinPeriods = new int[totalOps];
        long[] defaultOutputs = new long[totalOps];
        int opIndex = 0;
        for (Map.Entry<Integer, ColumnWindowOps> entry: groupedOps.entrySet()) {
          int columnIndex = entry.getKey();
          for (AggregationOverWindow operation: entry.getValue().operations()) {
            aggColumnIndexes[opIndex] = columnIndex;
            aggInstances[opIndex] = operation.createNativeInstance();
            Scalar p = operation.getWindowOptions().getPrecedingScalar();
            aggPrecedingWindows[opIndex] = p == null || !p.isValid() ? 0 : p.getInt();
            Scalar f = operation.getWindowOptions().getFollowingScalar();
            aggFollowingWindows[opIndex] = f == null || ! f.isValid() ? 1 : f.getInt();
            aggMinPeriods[opIndex] = operation.getWindowOptions().getMinPeriods();
            defaultOutputs[opIndex] = operation.getDefaultOutput();
            opIndex++;
          }
        }
        assert opIndex == totalOps : opIndex + " == " + totalOps;

        try (Table aggregate = new Table(rollingWindowAggregate(
            operation.table.nativeHandle,
            operation.indices,
            defaultOutputs,
            aggColumnIndexes,
            aggInstances, aggMinPeriods, aggPrecedingWindows, aggFollowingWindows,
            groupByOptions.getIgnoreNullKeys()))) {
          // prepare the final table
          ColumnVector[] finalCols = new ColumnVector[windowAggregates.length];

          int inputColumn = 0;
          // Now get the aggregation columns
          for (ColumnWindowOps ops: groupedOps.values()) {
            for (List<Integer> indices: ops.outputIndices()) {
              for (int outIndex: indices) {
                finalCols[outIndex] = aggregate.getColumn(inputColumn);
              }
              inputColumn++;
            }
          }
          return new Table(finalCols);
        }
      } finally {
        Aggregation.close(aggInstances);
      }
    }

    /**
     * Computes range-based window aggregation functions on the Table/projection,
     * based on windows specified in the argument.
     * 
     * This method enables queries such as the following SQL:
     * 
     *  SELECT user_id, 
     *         MAX(sales_amt) OVER(PARTITION BY user_id ORDER BY date 
     *                             RANGE BETWEEN INTERVAL 1 DAY PRECEDING and CURRENT ROW)
     *  FROM my_sales_table WHERE ...
     * 
     * Each window-aggregation is represented by a different {@link AggregationOverWindow} argument,
     * indicating:
     *  1. the {@link Aggregation.Kind},
     *  2. the index for the timestamp column to base the window definitions on
     *  2. the number of DAYS preceding and following the current row's date, to consider in the window
     *  3. the minimum number of observations within the defined window
     * 
     * This method returns a {@link Table} instance, with one result column for each specified
     * window aggregation.
     * 
     * In this example, for the following input:
     * 
     *  [ // user,  sales_amt,  YYYYMMDD (date)  
     *    { "user1",   10,      20200101    },
     *    { "user2",   20,      20200101    },
     *    { "user1",   20,      20200102    },
     *    { "user1",   10,      20200103    },
     *    { "user2",   30,      20200101    },
     *    { "user2",   80,      20200102    },
     *    { "user1",   50,      20200107    },
     *    { "user1",   60,      20200107    },
     *    { "user2",   40,      20200104    }
     *  ]
     * 
     * Partitioning (grouping) by `user_id`, and ordering by `date` yields the following `sales_amt` vector 
     * (with 2 groups, one for each distinct `user_id`):
     * 
     * Date :(202001-)  [ 01,  02,  03,  07,  07,    01,   01,   02,  04 ]
     * Input:           [ 10,  20,  10,  50,  60,    20,   30,   80,  40 ]
     *                    <-------user1-------->|<---------user2--------->
     * 
     * The SUM aggregation is applied, with 1 day preceding, and 1 day following, with a minimum of 1 period. 
     * The aggregation window is thus 3 *days* wide, yielding the following output column:
     * 
     *  Results:        [ 30,  40,  30,  110, 110,  130,  130,  130,  40 ]
     * 
     * @param windowAggregates the window-aggregations to be performed
     * @return Table instance, with each column containing the result of each aggregation.
     * @throws IllegalArgumentException if the window arguments are not of type
     * {@link WindowOptions.FrameType#RANGE} or the orderBys are not of (Boolean-exclusive) integral type
     * i.e. the timestamp-column was not specified for the aggregation.
     */
    public Table aggregateWindowsOverRanges(AggregationOverWindow... windowAggregates) {
      // To improve performance and memory we want to remove duplicate operations
      // and also group the operations by column so hopefully cudf can do multiple aggregations
      // in a single pass.

      // Use a tree map to make debugging simpler (columns are all in the same order)
      TreeMap<Integer, ColumnWindowOps> groupedOps = new TreeMap<>(); // Map agg-col-id -> Agg ColOp.
      // Total number of operations that will need to be done.
      int totalOps = 0;
      for (int outputIndex = 0; outputIndex < windowAggregates.length; outputIndex++) {
        AggregationOverWindow agg = windowAggregates[outputIndex];
        if (agg.getWindowOptions().getFrameType() != WindowOptions.FrameType.RANGE) {
          throw new IllegalArgumentException("Expected range-based window specification. Unexpected window type: "
              + agg.getWindowOptions().getFrameType());
        }

        DType orderByType = operation.table.getColumn(agg.getWindowOptions().getOrderByColumnIndex()).getType();
        switch (orderByType.getTypeId()) {
          case INT8:
          case INT16:
          case INT32:
          case INT64:
          case UINT8:
          case UINT16:
          case UINT32:
          case UINT64:
          case TIMESTAMP_MILLISECONDS:
          case TIMESTAMP_SECONDS:
          case TIMESTAMP_DAYS:
          case TIMESTAMP_NANOSECONDS:
          case TIMESTAMP_MICROSECONDS:
            break;
          default:
            throw new IllegalArgumentException("Expected range-based window orderBy's " +
                "type: integral (Boolean-exclusive) and timestamp");
        }

        ColumnWindowOps ops = groupedOps.computeIfAbsent(agg.getColumnIndex(), (idx) -> new ColumnWindowOps());
        totalOps += ops.add(agg, outputIndex);
      }

      int[] aggColumnIndexes = new int[totalOps];
      int[] orderByColumnIndexes = new int[totalOps];
      boolean[] isOrderByOrderAscending = new boolean[totalOps];
      long[] aggInstances = new long[totalOps];
      long[] aggPrecedingWindows = new long[totalOps];
      long[] aggFollowingWindows = new long[totalOps];
      try {
        boolean[] aggPrecedingWindowsUnbounded = new boolean[totalOps];
        boolean[] aggFollowingWindowsUnbounded = new boolean[totalOps];
        int[] aggMinPeriods = new int[totalOps];
        int opIndex = 0;
        for (Map.Entry<Integer, ColumnWindowOps> entry: groupedOps.entrySet()) {
          int columnIndex = entry.getKey();
          for (AggregationOverWindow op: entry.getValue().operations()) {
            aggColumnIndexes[opIndex] = columnIndex;
            aggInstances[opIndex] = op.createNativeInstance();
            Scalar p = op.getWindowOptions().getPrecedingScalar();
            Scalar f = op.getWindowOptions().getFollowingScalar();
            if ((p == null || !p.isValid()) && !op.getWindowOptions().isUnboundedPreceding()) {
              throw new IllegalArgumentException("Some kind of preceding must be set and a preceding column is not currently supported");
            }
            if ((f == null || !f.isValid()) && !op.getWindowOptions().isUnboundedFollowing()) {
              throw new IllegalArgumentException("some kind of following must be set and a follow column is not currently supported");
            }
            aggPrecedingWindows[opIndex] = p == null ? 0 : p.getScalarHandle();
            aggFollowingWindows[opIndex] = f == null ? 0 : f.getScalarHandle();
            aggPrecedingWindowsUnbounded[opIndex] = op.getWindowOptions().isUnboundedPreceding();
            aggFollowingWindowsUnbounded[opIndex] = op.getWindowOptions().isUnboundedFollowing();
            aggMinPeriods[opIndex] = op.getWindowOptions().getMinPeriods();
            assert (op.getWindowOptions().getFrameType() == WindowOptions.FrameType.RANGE);
            orderByColumnIndexes[opIndex] = op.getWindowOptions().getOrderByColumnIndex();
            isOrderByOrderAscending[opIndex] = op.getWindowOptions().isOrderByOrderAscending();
            if (op.getDefaultOutput() != 0) {
              throw new IllegalArgumentException("Operations with a default output are not " +
                  "supported on time based rolling windows");
            }

            opIndex++;
          }
        }
        assert opIndex == totalOps : opIndex + " == " + totalOps;

        try (Table aggregate = new Table(rangeRollingWindowAggregate(
            operation.table.nativeHandle,
            operation.indices,
            orderByColumnIndexes,
            isOrderByOrderAscending,
            aggColumnIndexes,
            aggInstances, aggMinPeriods, aggPrecedingWindows, aggFollowingWindows,
            aggPrecedingWindowsUnbounded, aggFollowingWindowsUnbounded,
            groupByOptions.getIgnoreNullKeys()))) {
          // prepare the final table
          ColumnVector[] finalCols = new ColumnVector[windowAggregates.length];

          int inputColumn = 0;
          // Now get the aggregation columns
          for (ColumnWindowOps ops: groupedOps.values()) {
            for (List<Integer> indices: ops.outputIndices()) {
              for (int outIndex: indices) {
                finalCols[outIndex] = aggregate.getColumn(inputColumn);
              }
              inputColumn++;
            }
          }
          return new Table(finalCols);
        }
      } finally {
        Aggregation.close(aggInstances);
      }
    }

    public Table scan(GroupByScanAggregationOnColumn... aggregates) {
      assert aggregates != null;

      // To improve performance and memory we want to remove duplicate operations
      // and also group the operations by column so hopefully cudf can do multiple aggregations
      // in a single pass.

      // Use a tree map to make debugging simpler (columns are all in the same order)
      TreeMap<Integer, ColumnOps> groupedOps = new TreeMap<>();
      // Total number of operations that will need to be done.
      int keysLength = operation.indices.length;
      int totalOps = 0;
      for (int outputIndex = 0; outputIndex < aggregates.length; outputIndex++) {
        GroupByScanAggregationOnColumn agg = aggregates[outputIndex];
        ColumnOps ops = groupedOps.computeIfAbsent(agg.getColumnIndex(), (idx) -> new ColumnOps());
        totalOps += ops.add(agg.getWrapped().getWrapped(), outputIndex + keysLength);
      }
      int[] aggColumnIndexes = new int[totalOps];
      long[] aggOperationInstances = new long[totalOps];
      try {
        int opIndex = 0;
        for (Map.Entry<Integer, ColumnOps> entry: groupedOps.entrySet()) {
          int columnIndex = entry.getKey();
          for (Aggregation operation: entry.getValue().operations()) {
            aggColumnIndexes[opIndex] = columnIndex;
            aggOperationInstances[opIndex] = operation.createNativeInstance();
            opIndex++;
          }
        }
        assert opIndex == totalOps : opIndex + " == " + totalOps;

        try (Table aggregate = new Table(groupByScan(
            operation.table.nativeHandle,
            operation.indices,
            aggColumnIndexes,
            aggOperationInstances,
            groupByOptions.getIgnoreNullKeys(),
            groupByOptions.getKeySorted(),
            groupByOptions.getKeysDescending(),
            groupByOptions.getKeysNullSmallest()))) {
          // prepare the final table
          ColumnVector[] finalCols = new ColumnVector[keysLength + aggregates.length];

          // get the key columns
          for (int aggIndex = 0; aggIndex < keysLength; aggIndex++) {
            finalCols[aggIndex] = aggregate.getColumn(aggIndex);
          }

          int inputColumn = keysLength;
          // Now get the aggregation columns
          for (ColumnOps ops: groupedOps.values()) {
            for (List<Integer> indices: ops.outputIndices()) {
              for (int outIndex: indices) {
                finalCols[outIndex] = aggregate.getColumn(inputColumn);
              }
              inputColumn++;
            }
          }
          return new Table(finalCols);
        }
      } finally {
        Aggregation.close(aggOperationInstances);
      }
    }

    public Table replaceNulls(ReplacePolicyWithColumn... replacements) {
      assert replacements != null;

      // TODO in the future perhaps to improve performance and memory we want to
      //  remove duplicate operations.

      boolean[] isPreceding = new boolean[replacements.length];
      int [] columnIndexes = new int[replacements.length];

      for (int index = 0; index < replacements.length; index++) {
        isPreceding[index] = replacements[index].policy.isPreceding;
        columnIndexes[index] = replacements[index].column;
      }

      return new Table(groupByReplaceNulls(
          operation.table.nativeHandle,
          operation.indices,
          columnIndexes,
          isPreceding,
          groupByOptions.getIgnoreNullKeys(),
          groupByOptions.getKeySorted(),
          groupByOptions.getKeysDescending(),
          groupByOptions.getKeysNullSmallest()));
    }

    /**
     * Splits the groups in a single table into separate tables according to the grouping keys.
     * Each split table represents a single group.
     *
     * This API will be used by some grouping related operators to process the data
     * group by group.
     *
     * Example:
     *   Grouping column index: 0
     *   Input: A table of 3 rows (two groups)
     *             a    1
     *             b    2
     *             b    3
     *
     * Result:
     *   Two tables, one group one table.
     *   Result[0]:
     *              a    1
     *
     *   Result[1]:
     *              b    2
     *              b    3
     *
     * Note, the order of the groups returned is NOT always the same with that in the input table.
     * The split is done in native to avoid copying the offset array to JVM.
     *
     * @return The tables split according to the groups in the table. NOTE: It is the
     * responsibility of the caller to close the result. Each table and column holds a
     * reference to the original buffer. But both the buffer and the table must be closed
     * for the memory to be released.
     */
    public ContiguousTable[] contiguousSplitGroups() {
      return Table.contiguousSplitGroups(
          operation.table.nativeHandle,
          operation.indices,
          groupByOptions.getIgnoreNullKeys(),
          groupByOptions.getKeySorted(),
          groupByOptions.getKeysDescending(),
          groupByOptions.getKeysNullSmallest());
    }

    /**
     * @deprecated use aggregateWindowsOverRanges
     */
    @Deprecated
    public Table aggregateWindowsOverTimeRanges(AggregationOverWindow... windowAggregates) {
      return aggregateWindowsOverRanges(windowAggregates);
    }
  }

  public static final class TableOperation {

    private final Operation operation;

    TableOperation(final Table table, final int... indices) {
      operation = new Operation(table, indices);
    }

    /**
     * Joins two tables on the join columns that are passed in.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).leftJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @param compareNullsEqual - Whether null join-key values should match or not.
     * @return the joined table.  The order of the columns returned will be join columns,
     * left non-join columns, right non-join columns.
     */
    public Table leftJoin(TableOperation rightJoinIndices, boolean compareNullsEqual) {
      return new Table(Table.leftJoin(operation.table.nativeHandle, operation.indices,
          rightJoinIndices.operation.table.nativeHandle, rightJoinIndices.operation.indices,
          compareNullsEqual));
    }

    /**
     * Joins two tables on the join columns that are passed in.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).leftJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @return the joined table.  The order of the columns returned will be join columns,
     * left non-join columns, right non-join columns.
     */
    public Table leftJoin(TableOperation rightJoinIndices) {
        return leftJoin(rightJoinIndices, true);
    }

    /**
     * Joins two tables on the join columns that are passed in.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).innerJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @param compareNullsEqual - Whether null join-key values should match or not.
     * @return the joined table.  The order of the columns returned will be join columns,
     * left non-join columns, right non-join columns.
     */
    public Table innerJoin(TableOperation rightJoinIndices, boolean compareNullsEqual) {
      return new Table(Table.innerJoin(operation.table.nativeHandle, operation.indices,
          rightJoinIndices.operation.table.nativeHandle, rightJoinIndices.operation.indices,
          compareNullsEqual));
    }

    /**
     * Joins two tables on the join columns that are passed in.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).innerJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @return the joined table.  The order of the columns returned will be join columns,
     * left non-join columns, right non-join columns.
     */
    public Table innerJoin(TableOperation rightJoinIndices) {
      return innerJoin(rightJoinIndices, true);
    }

    /**
     * Joins two tables on the join columns that are passed in.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).fullJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @param compareNullsEqual - Whether null join-key values should match or not.
     * @return the joined table.  The order of the columns returned will be join columns,
     * left non-join columns, right non-join columns.
     */
    public Table fullJoin(TableOperation rightJoinIndices, boolean compareNullsEqual) {
      return new Table(Table.fullJoin(operation.table.nativeHandle, operation.indices,
              rightJoinIndices.operation.table.nativeHandle, rightJoinIndices.operation.indices,
              compareNullsEqual));
    }

    /**
     * Joins two tables on the join columns that are passed in.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).fullJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @return the joined table.  The order of the columns returned will be join columns,
     * left non-join columns, right non-join columns.
     */
    public Table fullJoin(TableOperation rightJoinIndices) {
      return fullJoin(rightJoinIndices, true);
    }

    /**
     * Performs a semi-join between a left table and a right table, returning only the rows from
     * the left table that match rows in the right table on the join keys.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).leftSemiJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @param compareNullsEqual - Whether null join-key values should match or not.
     * @return the left semi-joined table.
     */
    public Table leftSemiJoin(TableOperation rightJoinIndices, boolean compareNullsEqual) {
      return new Table(Table.leftSemiJoin(operation.table.nativeHandle, operation.indices,
          rightJoinIndices.operation.table.nativeHandle, rightJoinIndices.operation.indices,
          compareNullsEqual));
    }

    /**
     * Performs a semi-join between a left table and a right table, returning only the rows from
     * the left table that match rows in the right table on the join keys.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).leftSemiJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @return the left semi-joined table.
     */
    public Table leftSemiJoin(TableOperation rightJoinIndices) {
      return leftSemiJoin(rightJoinIndices, true);
    }

    /**
     * Performs an anti-join between a left table and a right table, returning only the rows from
     * the left table that do not match rows in the right table on the join keys.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).leftAntiJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @param compareNullsEqual - Whether null join-key values should match or not.
     * @return the left anti-joined table.
     */
    public Table leftAntiJoin(TableOperation rightJoinIndices, boolean compareNullsEqual) {
      return new Table(Table.leftAntiJoin(operation.table.nativeHandle, operation.indices,
          rightJoinIndices.operation.table.nativeHandle, rightJoinIndices.operation.indices,
          compareNullsEqual));
    }

    /**
     * Performs an anti-join between a left table and a right table, returning only the rows from
     * the left table that do not match rows in the right table on the join keys.
     * Usage:
     * Table t1 ...
     * Table t2 ...
     * Table result = t1.onColumns(0,1).leftAntiJoin(t2.onColumns(2,3));
     * @param rightJoinIndices - Indices of the right table to join on
     * @return the left anti-joined table.
     */
    public Table leftAntiJoin(TableOperation rightJoinIndices) {
      return leftAntiJoin(rightJoinIndices, true);
    }

    /**
     * Hash partition a table into the specified number of partitions. Uses the default MURMUR3
     * hashing.
     * @param numberOfPartitions - number of partitions to use
     * @return - {@link PartitionedTable} - Table that exposes a limited functionality of the
     * {@link Table} class
     */
    public PartitionedTable hashPartition(int numberOfPartitions) {
      return hashPartition(HashType.MURMUR3, numberOfPartitions);
    }

    /**
     * Hash partition a table into the specified number of partitions.
     * @param type the type of hash to use. Depending on the type of hash different restrictions
     *             on the hash column(s) may exist. Not all hash functions are guaranteed to work
     *             besides IDENTITY and MURMUR3.
     * @param numberOfPartitions - number of partitions to use
     * @return {@link PartitionedTable} - Table that exposes a limited functionality of the
     * {@link Table} class
     */
    public PartitionedTable hashPartition(HashType type, int numberOfPartitions) {
      int[] partitionOffsets = new int[numberOfPartitions];
      return new PartitionedTable(new Table(Table.hashPartition(
          operation.table.nativeHandle,
          operation.indices,
          type.nativeId,
          partitionOffsets.length,
          partitionOffsets)), partitionOffsets);
    }

    /**
     * Hash partition a table into the specified number of partitions.
     * @deprecated Use {@link #hashPartition(int)}
     * @param numberOfPartitions - number of partitions to use
     * @return - {@link PartitionedTable} - Table that exposes a limited functionality of the
     * {@link Table} class
     */
    @Deprecated
    public PartitionedTable partition(int numberOfPartitions) {
      return hashPartition(numberOfPartitions);
    }
  }

  /////////////////////////////////////////////////////////////////////////////
  // BUILDER
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Create a table on the GPU with data from the CPU.  This is not fast and intended mostly for
   * tests.
   */
  public static final class TestBuilder {
    private final List<DataType> types = new ArrayList<>();
    private final List<Object> typeErasedData = new ArrayList<>();

    public TestBuilder column(String... values) {
      types.add(new BasicType(true, DType.STRING));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Boolean... values) {
      types.add(new BasicType(true, DType.BOOL8));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Byte... values) {
      types.add(new BasicType(true, DType.INT8));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Short... values) {
      types.add(new BasicType(true, DType.INT16));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Integer... values) {
      types.add(new BasicType(true, DType.INT32));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Long... values) {
      types.add(new BasicType(true, DType.INT64));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Float... values) {
      types.add(new BasicType(true, DType.FLOAT32));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Double... values) {
      types.add(new BasicType(true, DType.FLOAT64));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(ListType dataType, List<?>... values) {
      types.add(dataType);
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(String[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.STRING)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Boolean[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.BOOL8)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Byte[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.INT8)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Short[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.INT16)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Integer[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.INT32)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Long[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.INT64)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Float[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.FLOAT32)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(Double[]... values) {
      types.add(new ListType(true, new BasicType(true, DType.FLOAT64)));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(StructType dataType, StructData... values) {
      types.add(dataType);
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder column(StructType dataType, StructData[]... values) {
      types.add(new ListType(true, dataType));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder timestampDayColumn(Integer... values) {
      types.add(new BasicType(true, DType.TIMESTAMP_DAYS));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder timestampNanosecondsColumn(Long... values) {
      types.add(new BasicType(true, DType.TIMESTAMP_NANOSECONDS));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder timestampMillisecondsColumn(Long... values) {
      types.add(new BasicType(true, DType.TIMESTAMP_MILLISECONDS));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder timestampMicrosecondsColumn(Long... values) {
      types.add(new BasicType(true, DType.TIMESTAMP_MICROSECONDS));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder timestampSecondsColumn(Long... values) {
      types.add(new BasicType(true, DType.TIMESTAMP_SECONDS));
      typeErasedData.add(values);
      return this;
    }

    public TestBuilder decimal32Column(int scale, Integer... unscaledValues) {
      types.add(new BasicType(true, DType.create(DType.DTypeEnum.DECIMAL32, scale)));
      typeErasedData.add(unscaledValues);
      return this;
    }

    public TestBuilder decimal32Column(int scale, RoundingMode mode, Double... values) {
      types.add(new BasicType(true, DType.create(DType.DTypeEnum.DECIMAL32, scale)));
      BigDecimal[] data = Arrays.stream(values).map((x) -> {
        if (x == null) return null;
        return BigDecimal.valueOf(x).setScale(-scale, mode);
      }).toArray(BigDecimal[]::new);
      typeErasedData.add(data);
      return this;
    }

    public TestBuilder decimal32Column(int scale, RoundingMode mode, String... values) {
      types.add(new BasicType(true, DType.create(DType.DTypeEnum.DECIMAL32, scale)));
      BigDecimal[] data = Arrays.stream(values).map((x) -> {
        if (x == null) return null;
        return new BigDecimal(x).setScale(-scale, mode);
      }).toArray(BigDecimal[]::new);
      typeErasedData.add(data);
      return this;
    }

    public TestBuilder decimal64Column(int scale, Long... unscaledValues) {
      types.add(new BasicType(true, DType.create(DType.DTypeEnum.DECIMAL64, scale)));
      typeErasedData.add(unscaledValues);
      return this;
    }

    public TestBuilder decimal64Column(int scale, RoundingMode mode, Double... values) {
      types.add(new BasicType(true, DType.create(DType.DTypeEnum.DECIMAL64, scale)));
      BigDecimal[] data = Arrays.stream(values).map((x) -> {
        if (x == null) return null;
        return BigDecimal.valueOf(x).setScale(-scale, mode);
      }).toArray(BigDecimal[]::new);
      typeErasedData.add(data);
      return this;
    }

    public TestBuilder decimal64Column(int scale, RoundingMode mode, String... values) {
      types.add(new BasicType(true, DType.create(DType.DTypeEnum.DECIMAL64, scale)));
      BigDecimal[] data = Arrays.stream(values).map((x) -> {
        if (x == null) return null;
        return new BigDecimal(x).setScale(-scale, mode);
      }).toArray(BigDecimal[]::new);
      typeErasedData.add(data);
      return this;
    }

    private static ColumnVector from(DType type, Object dataArray) {
      ColumnVector ret = null;
      switch (type.typeId) {
        case STRING:
          ret = ColumnVector.fromStrings((String[]) dataArray);
          break;
        case BOOL8:
          ret = ColumnVector.fromBoxedBooleans((Boolean[]) dataArray);
          break;
        case INT8:
          ret = ColumnVector.fromBoxedBytes((Byte[]) dataArray);
          break;
        case INT16:
          ret = ColumnVector.fromBoxedShorts((Short[]) dataArray);
          break;
        case INT32:
          ret = ColumnVector.fromBoxedInts((Integer[]) dataArray);
          break;
        case INT64:
          ret = ColumnVector.fromBoxedLongs((Long[]) dataArray);
          break;
        case TIMESTAMP_DAYS:
          ret = ColumnVector.timestampDaysFromBoxedInts((Integer[]) dataArray);
          break;
        case TIMESTAMP_SECONDS:
          ret = ColumnVector.timestampSecondsFromBoxedLongs((Long[]) dataArray);
          break;
        case TIMESTAMP_MILLISECONDS:
          ret = ColumnVector.timestampMilliSecondsFromBoxedLongs((Long[]) dataArray);
          break;
        case TIMESTAMP_MICROSECONDS:
          ret = ColumnVector.timestampMicroSecondsFromBoxedLongs((Long[]) dataArray);
          break;
        case TIMESTAMP_NANOSECONDS:
          ret = ColumnVector.timestampNanoSecondsFromBoxedLongs((Long[]) dataArray);
          break;
        case FLOAT32:
          ret = ColumnVector.fromBoxedFloats((Float[]) dataArray);
          break;
        case FLOAT64:
          ret = ColumnVector.fromBoxedDoubles((Double[]) dataArray);
          break;
        case DECIMAL32:
        case DECIMAL64:
          int scale = type.getScale();
          if (dataArray instanceof Integer[]) {
            BigDecimal[] data = Arrays.stream(((Integer[]) dataArray))
                .map((i) -> i == null ? null : BigDecimal.valueOf(i, -scale))
                .toArray(BigDecimal[]::new);
            ret = ColumnVector.build(type, data.length, (b) -> b.appendBoxed(data));
          } else if (dataArray instanceof Long[]) {
            BigDecimal[] data = Arrays.stream(((Long[]) dataArray))
                .map((i) -> i == null ? null : BigDecimal.valueOf(i, -scale))
                .toArray(BigDecimal[]::new);
            ret = ColumnVector.build(type, data.length, (b) -> b.appendBoxed(data));
          } else if (dataArray instanceof BigDecimal[]) {
            BigDecimal[] data = (BigDecimal[]) dataArray;
            ret = ColumnVector.build(type, data.length, (b) -> b.appendBoxed(data));
          } else {
            throw new IllegalArgumentException(
                "Data array of invalid type(" + dataArray.getClass() + ") to build decimal column");
          }
          break;
        default:
          throw new IllegalArgumentException(type + " is not supported yet");
      }
      return ret;
    }

    @SuppressWarnings("unchecked")
    private static <T> ColumnVector fromLists(DataType dataType, Object[] dataArray) {
      List[] dataLists = new List[dataArray.length];
      for (int i = 0; i < dataLists.length; ++i) {
        // The element in dataArray can be an array or list, because the below overloaded
        // version accepts a List of Array as rows.
        //  `public TestBuilder column(ListType dataType, List<?>... values)`
        Object dataList = dataArray[i];
        dataLists[i] = dataList == null ? null :
            (dataList instanceof List ? (List)dataList : Arrays.asList((Object[])dataList));
      }
      return ColumnVector.fromLists(dataType, dataLists);
    }

    private static ColumnVector fromStructs(DataType dataType, StructData[] dataArray) {
      return ColumnVector.fromStructs(dataType, dataArray);
    }

    public Table build() {
      List<ColumnVector> columns = new ArrayList<>(types.size());
      try {
        for (int i = 0; i < types.size(); i++) {
          DataType dataType = types.get(i);
          DType dtype = dataType.getType();
          Object dataArray = typeErasedData.get(i);
          if (dtype.isNestedType()) {
            if (dtype.equals(DType.LIST)) {
              columns.add(fromLists(dataType, (Object[]) dataArray));
            } else if (dtype.equals(DType.STRUCT)) {
              columns.add(fromStructs(dataType, (StructData[]) dataArray));
            } else {
              throw new IllegalStateException("Unexpected nested type: " + dtype);
            }
          } else {
            columns.add(from(dtype, dataArray));
          }
        }
        return new Table(columns.toArray(new ColumnVector[columns.size()]));
      } finally {
        for (ColumnVector cv : columns) {
          cv.close();
        }
      }
    }
  }
}
