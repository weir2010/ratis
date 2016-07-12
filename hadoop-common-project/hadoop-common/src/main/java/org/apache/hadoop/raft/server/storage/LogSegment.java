/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.raft.server.storage;

import com.google.common.base.Preconditions;
import com.google.protobuf.CodedOutputStream;
import org.apache.hadoop.raft.proto.RaftProtos.LogEntryProto;
import org.apache.hadoop.raft.server.RaftConstants;
import org.apache.hadoop.raft.util.RaftUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory cache for a log segment file. All the updates will be first written
 * into LogSegment then into corresponding files in the same order.
 *
 * This class will be protected by the RaftServer's lock.
 */
class LogSegment implements Comparable<Long> {
  static class LogRecord {
    /** starting offset in the file */
    final long offset;
    final LogEntryProto entry;

    LogRecord(long offset, LogEntryProto entry) {
      this.offset = offset;
      this.entry = entry;
    }
  }

  static class SegmentFileInfo {
    final long startIndex;
    final long endIndex;
    final boolean isOpen;
    final long targetLength;

    SegmentFileInfo(long start, long end, boolean isOpen, long targetLength) {
      this.startIndex = start;
      this.endIndex = end;
      this.isOpen = isOpen;
      this.targetLength = targetLength;
    }
  }

  private boolean isOpen;
  private final List<LogRecord> records = new ArrayList<>();
  private long totalSize;
  private final long startIndex;
  private long endIndex;

  private LogSegment(boolean isOpen, long start, long end) {
    this.isOpen = isOpen;
    this.startIndex = start;
    this.endIndex = end;
    totalSize = SegmentedRaftLog.HEADER.length;
  }

  static LogSegment newOpenSegment(long start) {
    Preconditions.checkArgument(start >= 0);
    return new LogSegment(true, start, start - 1);
  }

  private static LogSegment newCloseSegment(long start, long end) {
    Preconditions.checkArgument(start >= 0 && end >= start);
    return new LogSegment(false, start, end);
  }

  static LogSegment loadSegment(File file, long start, long end, boolean isOpen)
      throws IOException {
    final LogSegment segment;
    try (LogInputStream in = new LogInputStream(file, start, end, isOpen)) {
      segment = isOpen ? LogSegment.newOpenSegment(start) :
          LogSegment.newCloseSegment(start, end);
      LogEntryProto next;
      LogEntryProto prev = null;
      while ((next = in.nextEntry()) != null) {
        if (prev != null) {
          Preconditions.checkState(next.getIndex() == prev.getIndex() + 1,
              "gap between entry %s and entry %s", prev, next);
        }
        segment.append(next);
        prev = next;
      }
    }

    // truncate padding if necessary
    if (file.length() > segment.getTotalSize()) {
      RaftUtils.truncateFile(file, segment.getTotalSize());
    }

    Preconditions.checkState(start == segment.records.get(0).entry.getIndex());
    if (!isOpen) {
      Preconditions.checkState(segment.getEndIndex() == end);
    }
    return segment;
  }

  long getStartIndex() {
    return startIndex;
  }

  long getEndIndex() {
    return endIndex;
  }

  boolean isOpen() {
    return isOpen;
  }

  int numOfEntries() {
    return (int) (endIndex - startIndex + 1);
  }

  boolean isFull() {
    return totalSize >= RaftConstants.LOG_SEGMENT_MAX_SIZE;
  }

  void append(LogEntryProto... entries) {
    Preconditions.checkState(isOpen(),
        "The log segment %s is not open for append", this.toString());
    Preconditions.checkArgument(entries != null && entries.length > 0);
    final long term = entries[0].getTerm();
    if (records.isEmpty()) {
      Preconditions.checkArgument(entries[0].getIndex() == startIndex,
          "gap between start index %s and first entry to append %s",
          startIndex, entries[0].getIndex());
    }
    for (LogEntryProto entry : entries) {
      // all these entries should be of the same term
      Preconditions.checkArgument(entry.getTerm() == term,
          "expected term:%s, term of the entry:%s", term, entry.getTerm());
      Preconditions.checkArgument(records.isEmpty() ||
          entry.getIndex() == getLastRecord().entry.getIndex() + 1,
          "gap between entries %s and %s", entry.getIndex(),
          getLastRecord().entry.getIndex());

      final LogRecord record = new LogRecord(totalSize, entry);
      records.add(record);
      final int serialized = entry.getSerializedSize();
      totalSize += serialized
          + CodedOutputStream.computeRawVarint32Size(serialized) + 4;
      endIndex = entry.getIndex();
    }
  }

  LogRecord getLogRecord(long index) {
    if (index >= startIndex && index <= endIndex) {
      return records.get((int) (index - startIndex));
    }
    return null;
  }

  LogRecord getLastRecord() {
    return records.isEmpty() ? null : records.get(records.size() - 1);
  }

  long getTotalSize() {
    return totalSize;
  }

  /**
   * Remove records from the given index (inclusive)
   */
  void truncate(long fromIndex) {
    Preconditions.checkArgument(fromIndex >= startIndex && fromIndex <= endIndex);
    LogRecord record = records.get((int) (fromIndex - startIndex));
    for (long index = fromIndex; index <= endIndex; index++) {
      records.remove((int)(index - startIndex));
    }
    totalSize = record.offset;
    isOpen = false;
    this.endIndex = fromIndex - 1;
  }

  void close() {
    Preconditions.checkState(isOpen());
    isOpen = false;
  }

  @Override
  public String toString() {
    return isOpen() ? "log-" + startIndex + "-inprogress" :
        "log-" + startIndex + "-" + endIndex;
  }

  @Override
  public int compareTo(Long l) {
    return this.getStartIndex() == l ? 0 : (this.getStartIndex() < l ? -1 : 1);
  }
}