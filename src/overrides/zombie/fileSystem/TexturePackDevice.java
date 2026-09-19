package zombie.fileSystem;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import zombie.ZomboidFileSystem;
import zombie.core.textures.TexturePackPage;
import zombie.core.textures.TexturePackPage.SubTextureInfo;
import zombie.fileSystem.FileSystem.TexturePackTextures;

public final class TexturePackDevice implements IFileDevice {
   static final int VERSION1 = 1;
   static final int VERSION_LATEST = 1;
   String name;
   String filename;
   int version = -1;
   final ArrayList<TexturePackDevice.Page> pages = new ArrayList<>();
   final HashMap<String, TexturePackDevice.Page> pagemap = new HashMap<>();
   final HashMap<String, TexturePackDevice.SubTexture> submap = new HashMap<>();
   int textureFlags;
   private pzopt.PackIndex pzoptIndex; // pzopt: page end offsets of a version-0 pack (see readPage)

   static {
      pzopt.Overrides.onClassLoaded("zombie.fileSystem.TexturePackDevice");
   }

   private static long skipInput(InputStream input, long length) throws IOException {
      long skipped = 0L;

      while (skipped < length) {
         long n = input.skip(length - skipped);
         if (n > 0L) {
            skipped += n;
         }
      }

      return skipped;
   }

   public TexturePackDevice(String name, int flags) {
      this.name = name;
      this.filename = ZomboidFileSystem.instance.getString("media/texturepacks/" + name + ".pack");
      this.textureFlags = flags;
   }

   public IFile createFile(IFile child) {
      return null;
   }

   public void destroyFile(IFile file) {
   }

   public InputStream createStream(String path, InputStream child) throws IOException {
      this.initMetaData();
      return new TexturePackDevice.TexturePackInputStream(path, this);
   }

   public void destroyStream(InputStream stream) {
      if (stream instanceof TexturePackDevice.TexturePackInputStream) {
      }
   }

   public String name() {
      return this.name;
   }

   public void getSubTextureInfo(TexturePackTextures result) throws IOException {
      this.initMetaData();

      for (TexturePackDevice.SubTexture subTexture : this.submap.values()) {
         zombie.fileSystem.FileSystem.SubTexture subTexture1 = new zombie.fileSystem.FileSystem.SubTexture(this.name(), subTexture.page.name, subTexture.info);
         result.put(subTexture.info.name, subTexture1);
      }
   }

   private void initMetaData() throws IOException {
      if (this.pages.isEmpty()) {
         try (
            FileInputStream fis = new FileInputStream(this.filename);
            BufferedInputStream bis = new BufferedInputStream(fis);
            TexturePackDevice.PositionInputStream input = new TexturePackDevice.PositionInputStream(bis);
         ) {
            input.mark(4);
            int m1 = input.read();
            int m2 = input.read();
            int m3 = input.read();
            int m4 = input.read();
            if (m1 == 80 && m2 == 90 && m3 == 80 && m4 == 75) {
               this.version = TexturePackPage.readInt(input);
               if (this.version < 1 || this.version > 1) {
                  throw new IOException("invalid .pack file version " + this.version);
               }
            } else {
               input.reset();
               this.version = 0;
            }

            int count = TexturePackPage.readInt(input);
            this.pzoptIndex = this.version == 0 && pzopt.Config.PACK_INDEX && pzopt.Overrides.enabled() ? pzopt.PackIndex.open(this.filename) : null;

            for (int i = 0; i < count; i++) {
               TexturePackDevice.Page page = this.readPage(input);
               this.pages.add(page);
               this.pagemap.put(page.name, page);

               for (SubTextureInfo sub : page.sub) {
                  this.submap.put(sub.name, new TexturePackDevice.SubTexture(page, sub));
               }
            }

            if (this.pzoptIndex != null) {
               this.pzoptIndex.save();
               this.pzoptIndex = null;
            }
         }
      }
   }

   private TexturePackDevice.Page readPage(TexturePackDevice.PositionInputStream input) throws IOException {
      TexturePackDevice.Page page = new TexturePackDevice.Page();
      String name = TexturePackPage.ReadString(input);
      int numEntries = TexturePackPage.readInt(input);
      boolean bMask = TexturePackPage.readInt(input) != 0;
      page.name = name;
      page.hasAlpha = bMask;

      for (int n = 0; n < numEntries; n++) {
         String entryName = TexturePackPage.ReadString(input);
         int a = TexturePackPage.readInt(input);
         int b = TexturePackPage.readInt(input);
         int c = TexturePackPage.readInt(input);
         int d = TexturePackPage.readInt(input);
         int e = TexturePackPage.readInt(input);
         int f = TexturePackPage.readInt(input);
         int g = TexturePackPage.readInt(input);
         int h = TexturePackPage.readInt(input);
         page.sub.add(new SubTextureInfo(a, b, c, d, e, f, g, h, entryName));
      }

      page.pngStart = input.getPosition();
      int id;
      if (this.version == 0) {
         // pzopt: the page end is looked up in the pack index instead of scanning the PNG bytes one at a time;
         // a page not in the index is scanned as stock and recorded (docs/plan-instant-load.md B5)
         Long pzoptEnd = this.pzoptIndex != null ? this.pzoptIndex.endByStart.get(page.pngStart) : null;
         if (pzoptEnd != null) {
            skipInput(input, pzoptEnd - page.pngStart);
         } else {
            do {
               id = TexturePackPage.readIntByte(input);
            } while (id != -559038737);

            if (this.pzoptIndex != null) {
               this.pzoptIndex.endByStart.put(page.pngStart, input.getPosition());
               this.pzoptIndex.dirty = true;
            }
         }
      } else {
         id = TexturePackPage.readInt(input);
         skipInput(input, id);
      }

      return page;
   }

   public boolean isAlpha(String page) {
      TexturePackDevice.Page page1 = this.pagemap.get(page);
      return page1.hasAlpha;
   }

   public int getTextureFlags() {
      return this.textureFlags;
   }

   static final class Page {
      String name;
      boolean hasAlpha;
      long pngStart = -1L;
      final ArrayList<SubTextureInfo> sub = new ArrayList<>();
   }

   public final class PositionInputStream extends FilterInputStream {
      private long pos;
      private long mark;

      public PositionInputStream(final InputStream in) {
         super(in);
      }

      public synchronized long getPosition() {
         return this.pos;
      }

      @Override
      public synchronized int read() throws IOException {
         int b = super.read();
         if (b >= 0) {
            this.pos++;
         }

         return b;
      }

      @Override
      public synchronized int read(byte[] b, int off, int len) throws IOException {
         int n = super.read(b, off, len);
         if (n > 0) {
            this.pos += n;
         }

         return n;
      }

      @Override
      public synchronized long skip(long skip) throws IOException {
         long n = super.skip(skip);
         if (n > 0L) {
            this.pos += n;
         }

         return n;
      }

      @Override
      public synchronized void mark(int readlimit) {
         super.mark(readlimit);
         this.mark = this.pos;
      }

      @Override
      public synchronized void reset() throws IOException {
         if (!this.markSupported()) {
            throw new IOException("Mark not supported.");
         }

         super.reset();
         this.pos = this.mark;
      }
   }

   static final class SubTexture {
      final TexturePackDevice.Page page;
      final SubTextureInfo info;

      SubTexture(TexturePackDevice.Page page, SubTextureInfo info) {
         this.page = page;
         this.info = info;
      }
   }

   static class TexturePackInputStream extends FileInputStream {
      TexturePackDevice device;

      TexturePackInputStream(String path, TexturePackDevice device) throws IOException {
         super(device.filename);
         this.device = device;
         TexturePackDevice.Page page = this.device.pagemap.get(path);
         if (page == null) {
            throw new FileNotFoundException();
         }

         TexturePackDevice.skipInput(this, page.pngStart);
         if (device.version >= 1) {
            int var4 = TexturePackPage.readInt(this);
         }
      }
   }
}
