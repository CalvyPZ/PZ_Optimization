package zombie.audio.parameters;

import java.util.ArrayList;
import zombie.audio.FMODGlobalParameter;
import zombie.audio.FMODParameterUtils;
import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.iso.IsoWorld;
import zombie.iso.zones.Zone;

public final class ParameterZone extends FMODGlobalParameter {
   private final String zoneName;
   private final ArrayList<Zone> zones = new ArrayList<>();

   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.audio.parameters.ParameterZone");
   }

   // pzopt: Config.SOUND_ZONE_CACHE. The value depends on the listener's integer square only (the zone scan and
   // the distances are all taken from fastfloor(x), fastfloor(y)), so it is reused while that square is unchanged,
   // for at most 30 frames so a zone added or removed under a standing player is still picked up.
   private int pzoptSquareX = Integer.MIN_VALUE;
   private int pzoptSquareY = Integer.MIN_VALUE;
   private int pzoptFrame = Integer.MIN_VALUE;
   private float pzoptValue;

   public ParameterZone(String name, String zoneName) {
      super(name);
      this.zoneName = zoneName;
   }

   public float calculateCurrentValue() {
      IsoGameCharacter player = FMODParameterUtils.getFirstListener();
      if (player == null) {
         return 40.0F;
      }

      if (pzopt.Config.SOUND_ZONE_CACHE && pzopt.Overrides.enabled()) { // pzopt: per-square memo
         int sx = PZMath.fastfloor(player.getX());
         int sy = PZMath.fastfloor(player.getY());
         int frame = IsoWorld.instance != null ? IsoWorld.instance.getFrameNo() : 0;
         if (sx == this.pzoptSquareX && sy == this.pzoptSquareY && frame - this.pzoptFrame < 30 && frame >= this.pzoptFrame) {
            return this.pzoptValue;
         }
         float v = this.pzoptCalculate(player);
         this.pzoptSquareX = sx;
         this.pzoptSquareY = sy;
         this.pzoptFrame = frame;
         this.pzoptValue = v;
         return v;
      }
      return this.pzoptCalculate(player);
   }

   private float pzoptCalculate(IsoGameCharacter player) { // pzopt: the stock body from here on

      int z = 0;
      this.zones.clear();
      IsoWorld.instance.metaGrid.getZonesIntersecting(PZMath.fastfloor(player.getX()) - 40, PZMath.fastfloor(player.getY()) - 40, 0, 80, 80, this.zones);
      float closestDistSq = Float.MAX_VALUE;

      for (int i = 0; i < this.zones.size(); i++) {
         Zone zone = this.zones.get(i);
         boolean bForestZone = "Forest".equalsIgnoreCase(this.zoneName) && !"DeepForest".equalsIgnoreCase(zone.getType()) && zone.getType().endsWith("Forest");
         if (bForestZone || this.zoneName.equalsIgnoreCase(zone.getType())) {
            if (zone.contains(PZMath.fastfloor(player.getX()), PZMath.fastfloor(player.getY()), 0)) {
               return 0.0F;
            }

            float centerX = zone.x + zone.w / 2.0F;
            float centerY = zone.y + zone.h / 2.0F;
            float dx = PZMath.max(PZMath.abs(player.getX() - centerX) - zone.w / 2.0F, 0.0F);
            float dy = PZMath.max(PZMath.abs(player.getY() - centerY) - zone.h / 2.0F, 0.0F);
            closestDistSq = PZMath.min(closestDistSq, dx * dx + dy * dy);
         }
      }

      return (int)PZMath.clamp(PZMath.sqrt(closestDistSq), 0.0F, 40.0F);
   }
}
