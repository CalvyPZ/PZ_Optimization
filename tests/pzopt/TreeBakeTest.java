package pzopt;

/**
 * The tree pass geometry (issue #5): a JUMBO tree's sprite rectangle in chunk-texture space, which neighbour
 * textures need a copy of it, and the depth tilt. Numbers are for 2x tiles (tile 128x64, level 192 px) and level 0-1
 * textures of 1024x1024: goX = 512, yoff = 2 * 192 + 96 (the JUMBO_L allowance) = 480.
 */
public class TreeBakeTest {
   public static void main(String[] args) {
      int ts = 2;
      float goX = 512.0F;
      float yoff = 480.0F;
      TreeBake.Rect r = new TreeBake.Rect();
      TreeBake.Rect own = new TreeBake.Rect();
      TreeBake.Rect other = new TreeBake.Rect();
      TreeBake.ownTextureRect(goX, 1024.0F, 1024.0F, own);
      Check.check(own.x0 == 0.0F && own.x1 == 1024.0F && own.y0 == 0.0F && own.y1 == 1024.0F, "own texture area");

      // an XL tree (5 tiles wide, 12 tile heights tall) on the east corner square (7, 0) of chunk (100, 200)
      float ox = TreeBake.offsetX("e_redmapleJUMBOXL_1_0", ts);
      float oy = TreeBake.offsetY("e_redmapleJUMBOXL_1_0", ts);
      Check.check(ox == 320.0F && oy == 704.0F, "XL offsets " + ox + "," + oy);
      TreeBake.spriteRect(807, 1600, 0, 100, 200, ox, oy, 640.0F, 768.0F, goX, yoff, ts, r);
      // square (7,0): screen x = 7 * 64 = 448 right of the chunk's north corner, y = 7 * 32 = 224 below it
      Check.check(r.x0 == 512.0F + 448.0F - 320.0F && r.x1 == r.x0 + 640.0F, "XL x " + r.x0 + ".." + r.x1);
      Check.check(r.y0 == 480.0F + 224.0F - 704.0F && r.y1 == r.y0 + 768.0F, "XL y " + r.y0 + ".." + r.y1);
      Check.check(r.ground == 480.0F + 224.0F + 64.0F, "ground row is the square's south corner: " + r.ground);
      Check.check(TreeBake.overlaps(r, own), "overlaps its own texture");
      Check.check(r.x1 > 1024.0F, "sticks out of its own texture on the right: " + r.x1);
      // the east neighbour (101, 200): its texture starts 512 px to the right and 256 px lower, and needs a copy
      TreeBake.neighbourTextureRect(1, 0, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
      Check.check(other.x0 == 512.0F && other.x1 == 1536.0F && other.y0 == 256.0F && other.y1 == 1280.0F, "east texture " + other.x0 + "," + other.y0);
      Check.check(TreeBake.needsCopy(r, other, own), "the east neighbour needs a copy");
      // seen from the east neighbour's own space the sprite is 512 px further left and 256 px higher
      TreeBake.spriteRect(807, 1600, 0, 101, 200, ox, oy, 640.0F, 768.0F, goX, yoff, ts, r);
      Check.check(r.x0 == 512.0F - 64.0F - 320.0F && r.y0 == 480.0F - 32.0F - 704.0F, "east neighbour frame " + r.x0 + "," + r.y0);
      // the west neighbour's texture covers only what the tree's own texture already has: no copy
      TreeBake.spriteRect(807, 1600, 0, 100, 200, ox, oy, 640.0F, 768.0F, goX, yoff, ts, r);
      TreeBake.neighbourTextureRect(-1, 0, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
      Check.check(!TreeBake.needsCopy(r, other, own), "the west neighbour needs no copy");
      // two chunks south-east: out of reach
      TreeBake.neighbourTextureRect(2, 2, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
      Check.check(!TreeBake.overlaps(r, other), "does not reach (102, 202)");

      // an XXL tree on the north corner square (0, 0): its crown is 896 px above the diamond top, the texture
      // ends 480 px above it, so the top 416 px need the north-west neighbour (99, 199), whose texture is 512 px higher
      float ox2 = TreeBake.offsetX("e_virginiapineJUMBOXXL_1_1", ts);
      float oy2 = TreeBake.offsetY("e_virginiapineJUMBOXXL_1_1", ts);
      TreeBake.spriteRect(800, 1600, 0, 100, 200, ox2, oy2, 896.0F, 1024.0F, goX, yoff, ts, r);
      Check.check(r.y0 == 480.0F - 960.0F, "XXL crown top " + r.y0);
      TreeBake.neighbourTextureRect(-1, -1, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
      Check.check(other.y0 == -512.0F && other.x0 == 0.0F, "north-west texture " + other.x0 + "," + other.y0);
      Check.check(TreeBake.needsCopy(r, other, own), "the north-west neighbour needs a copy of the crown top");
      TreeBake.neighbourTextureRect(1, 1, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
      Check.check(!TreeBake.needsCopy(r, other, own), "the south-east neighbour has nothing the own texture lacks");

      // a normal tree on a middle square fits its own texture: no neighbour needs a copy
      float ox3 = TreeBake.offsetX("vegetation_trees_01_5", ts);
      float oy3 = TreeBake.offsetY("vegetation_trees_01_5", ts);
      Check.check(ox3 == 64.0F && oy3 == 192.0F, "normal tree offsets " + ox3 + "," + oy3);
      Check.check(TreeBake.spriteScale(2, 64, 128) == 2.0F && TreeBake.spriteScale(2, 128, 256) == 1.0F, "1x-only textures draw doubled");
      TreeBake.spriteRect(803, 1603, 0, 100, 200, ox3, oy3, 128.0F, 256.0F, goX, yoff, ts, r);
      Check.check(r.x0 >= 0.0F && r.x1 <= 1024.0F && r.y0 >= 0.0F && r.y1 <= 1024.0F, "normal tree inside " + r.x0 + "," + r.y0);
      for (int dwy = -2; dwy <= 2; dwy++) {
         for (int dwx = -2; dwx <= 2; dwx++) {
            TreeBake.neighbourTextureRect(dwx, dwy, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
            Check.check(!TreeBake.needsCopy(r, other, own), "no copy for " + dwx + "," + dwy);
         }
      }
      // the same normal tree on the east corner square ends exactly at the texture border: still no copy
      TreeBake.spriteRect(807, 1600, 0, 100, 200, ox3, oy3, 128.0F, 256.0F, goX, yoff, ts, r);
      TreeBake.neighbourTextureRect(1, 0, goX, yoff, yoff, 1024.0F, 1024.0F, ts, other);
      Check.check(r.x1 == 1024.0F && !TreeBake.needsCopy(r, other, own), "corner tree fits: no east copy");

      // high-res texture (camera zoom < 0.75): the logical area is half the texture around goX = texture width / 2
      TreeBake.ownTextureRect(1024.0F, 1024.0F, 1024.0F, other);
      Check.check(other.x0 == 512.0F && other.x1 == 1536.0F, "high-res logical area");

      // depth: the ground row keeps the base, one level (192 px) up is one level's depth nearer
      float base = 0.0100F;
      Check.check(TreeBake.depthAtRow(base, 768.0F, 768.0F, ts) == base, "ground row keeps the base");
      float up = TreeBake.depthAtRow(base, 768.0F, 768.0F - 192.0F, ts);
      Check.check(Math.abs(up - (base - TreeBake.DEPTH_PER_LEVEL)) < 1.0E-7F, "one level up: " + up);
      float top = TreeBake.depthAtRow(base, 768.0F, 768.0F - 5 * 192.0F, ts);
      Check.check(top < 0.0F, "five levels up goes below the texture's range (GL clamps it to 0): " + top);
      System.out.println("TreeBakeTest: ok");
   }
}
