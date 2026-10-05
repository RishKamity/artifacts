package artifacts.client.item.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartNames;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Renders an artifact model so that it follows the pose of the entity it is worn on.
 * <p>
 * The pose of the wearer's model is captured when this model is created, which happens while the
 * wearer's render layers are being submitted. At that point the wearer's model holds the pose that
 * is actually drawn, including any pose applied by animation mods such as Entity Model Features
 * (used by Fresh Animations). Re-running the wearer's {@code setupAnim} later, during the deferred
 * draw, would rebuild the plain vanilla pose and discard those animations.
 * <p>
 * Entity Model Features may also animate custom child bones named {@code EMF_<part>} under the
 * vanilla parts. When such a bone exists, its transform is combined with the vanilla part's
 * transform, so the artifact follows the animated bone rather than the vanilla part.
 */
public final class TransformCopyingHumanoidModel<S extends HumanoidRenderState> extends Model<S> {

    private static final String EMF_PART_PREFIX = "EMF_";

    private final HumanoidModel<HumanoidRenderState> delegate;

    private final PartTransform root;
    private final PartTransform head;
    private final PartTransform hat;
    private final PartTransform body;
    private final PartTransform leftArm;
    private final PartTransform rightArm;
    private final PartTransform leftLeg;
    private final PartTransform rightLeg;

    public static <S extends HumanoidRenderState> TransformCopyingHumanoidModel<S> create(HumanoidModel<? super S> source, HumanoidModel<HumanoidRenderState> delegate) {
        return new TransformCopyingHumanoidModel<>(source, delegate);
    }

    private TransformCopyingHumanoidModel(HumanoidModel<? super S> source, HumanoidModel<HumanoidRenderState> delegate) {
        super(delegate.root(), delegate::renderType);
        this.delegate = delegate;

        // capture the wearer's current (possibly animated) pose
        this.root = PartTransform.of(source.root());
        this.head = PartTransform.capture(source.head, PartNames.HEAD);
        this.hat = PartTransform.capture(source.hat, PartNames.HAT);
        this.body = PartTransform.capture(source.body, PartNames.BODY);
        this.leftArm = PartTransform.capture(source.leftArm, PartNames.LEFT_ARM);
        this.rightArm = PartTransform.capture(source.rightArm, PartNames.RIGHT_ARM);
        this.leftLeg = PartTransform.capture(source.leftLeg, PartNames.LEFT_LEG);
        this.rightLeg = PartTransform.capture(source.rightLeg, PartNames.RIGHT_LEG);
    }

    @Override
    public void setupAnim(S renderState) {
        // reset pose & setup animations
        delegate.setupAnim(renderState);
        // copy the captured wearer pose to the delegate
        root.applyTo(delegate.root());
        head.applyTo(delegate.head);
        hat.applyTo(delegate.hat);
        body.applyTo(delegate.body);
        leftArm.applyTo(delegate.leftArm);
        rightArm.applyTo(delegate.rightArm);
        leftLeg.applyTo(delegate.leftLeg);
        rightLeg.applyTo(delegate.rightLeg);
    }

    private record PartTransform(
            float x, float y, float z,
            float xRot, float yRot, float zRot,
            float xScale, float yScale, float zScale
    ) {

        private static PartTransform of(ModelPart part) {
            return new PartTransform(
                    part.x, part.y, part.z,
                    part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale
            );
        }

        /**
         * Captures the transform of a vanilla part. If Entity Model Features added an animated
         * {@code EMF_<name>} bone under it, the two transforms are combined into one.
         */
        private static PartTransform capture(ModelPart part, String name) {
            String emfName = EMF_PART_PREFIX + name;
            if (!part.hasChild(emfName)) {
                return of(part);
            }

            ModelPart emfPart = part.getChild(emfName);

            PoseStack poseStack = new PoseStack();
            part.translateAndRotate(poseStack);
            emfPart.translateAndRotate(poseStack);
            Matrix4f matrix = poseStack.last().pose();
            // EMF bones have their own rest pivot (taken from the model file), which usually differs from
            // the vanilla part's pivot. Only apply the bone's movement relative to its rest pose, so that
            // artifacts, which are modelled around the vanilla pivot, keep their vanilla position at rest.
            matrix.mul(poseMatrix(emfPart.getInitialPose()).invert());

            Vector3f translation = matrix.getTranslation(new Vector3f());
            Vector3f scale = matrix.getScale(new Vector3f());
            Vector3f rotation = matrix.getNormalizedRotation(new Quaternionf()).getEulerAnglesZYX(new Vector3f());

            // ModelPart positions are in pixels (1/16 of a block)
            return new PartTransform(
                    translation.x * 16, translation.y * 16, translation.z * 16,
                    rotation.x, rotation.y, rotation.z,
                    scale.x, scale.y, scale.z
            );
        }

        /**
         * Builds the same transform {@link ModelPart#translateAndRotate} applies for the given pose.
         */
        private static Matrix4f poseMatrix(PartPose pose) {
            return new Matrix4f()
                    .translation(pose.x() / 16.0F, pose.y() / 16.0F, pose.z() / 16.0F)
                    .rotate(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()))
                    .scale(pose.xScale(), pose.yScale(), pose.zScale());
        }

        private void applyTo(ModelPart delegate) {
            delegate.x = x;
            delegate.y = y;
            delegate.z = z;
            delegate.xRot = xRot;
            delegate.yRot = yRot;
            delegate.zRot = zRot;
            // multiply to retain the necklace default part pose
            // should be fine as long as setupAnim on the delegate doesn't change part scale anywhere else
            delegate.xScale *= xScale;
            delegate.yScale *= yScale;
            delegate.zScale *= zScale;
        }
    }
}
