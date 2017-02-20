package org.allenai.scienceparse;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

class MarkableFileInputStream extends FilterInputStream {
  private FileChannel fileChannel;
  private static final long NOT_MARKED = -1;
  private static final long MARK_FAILED = -2;
  private long mark = NOT_MARKED;

  public MarkableFileInputStream(final FileInputStream fis) {
    super(fis);
    fileChannel = fis.getChannel();
    mark(0);
  }

  @Override
  public boolean markSupported() {
    return true;
  }

  @Override
  public synchronized void mark(int readlimit) {
    try {
      mark = fileChannel.position();
    } catch (IOException ex) {
      mark = MARK_FAILED;
    }
  }

  @Override
  public synchronized void reset() throws IOException {
    if(mark == NOT_MARKED)
      throw new IOException("not marked");
    else if(mark == MARK_FAILED)
      throw new IOException("previous mark failed");
    else
      fileChannel.position(mark);
  }

  @Override
  public void close() throws IOException {
    super.close();
  }
}
