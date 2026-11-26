package com.launcher.ui;

import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

public class SkinPreview3D extends StackPane {
    private final Group avatar = new Group();
    private final Rotate rotateX = new Rotate(-8, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(-22, Rotate.Y_AXIS);
    private double dragX, dragY;
    private double cameraDistance = -145;
    private int textureScale = 1;
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final SubScene subScene;

    public SkinPreview3D() {
        setMinHeight(230);
        setPrefHeight(270);
        setStyle("-fx-background-color: #111827; -fx-background-radius: 14;");
        avatar.getTransforms().addAll(rotateY, rotateX);
        Group world = new Group(avatar, new AmbientLight(Color.color(0.78, 0.78, 0.82)));
        PointLight key = new PointLight(Color.WHITE);
        key.setTranslateX(-30); key.setTranslateY(-35); key.setTranslateZ(-45);
        world.getChildren().add(key);
        camera.setNearClip(0.1); camera.setFarClip(1000); camera.setFieldOfView(34); camera.setTranslateZ(cameraDistance);
        subScene = new SubScene(world, 420, 270, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#111827"));
        subScene.setCamera(camera);
        subScene.widthProperty().bind(widthProperty());
        subScene.heightProperty().bind(heightProperty());
        getChildren().add(subScene);
        installMouseControls();
        setDefaultSkin();
    }

    public void setSkin(Image skin, boolean slim) {
        if (skin == null || skin.getPixelReader() == null) { setDefaultSkin(); return; }
        avatar.getChildren().clear();
        Image crispSkin = upscaleNearest(skin);
        textureScale = (crispSkin.getWidth() >= skin.getWidth() * 2) ? 8 : 1;
        skin = crispSkin;
        int height = (int) skin.getHeight();
        addPart(skin, 0, -16, 0, 8, 8, 8, rect(8,8,16,16), rect(24,8,32,16), rect(0,8,8,16), rect(16,8,24,16), rect(8,0,16,8), rect(16,0,24,8));
        addPart(skin, 0, -6, 0, 8, 12, 4, rect(20,20,28,32), rect(32,20,40,32), rect(16,20,20,32), rect(28,20,32,32), rect(20,16,28,20), rect(28,16,36,20));
        if (height >= 64) {
            addPart(skin, 0, -16, 0, 8.5, 8.5, 8.5, rect(40,8,48,16), rect(56,8,64,16), rect(32,8,40,16), rect(48,8,56,16), rect(40,0,48,8), rect(48,0,56,8));
            addPart(skin, 0, -6, 0, 8.5, 12.5, 4.5, rect(20,36,28,48), rect(32,36,40,48), rect(16,36,20,48), rect(28,36,32,48), rect(20,32,28,36), rect(28,32,36,36));
        }
        int armWidth = slim ? 3 : 4;
        if (height >= 64) {
            addArm(skin, -6, armWidth, false, slim);
            addArm(skin, 6, armWidth, true, slim);
            addPart(skin, -2, 6, 0, 4, 12, 4,
                    rect(4,20,8,32), rect(12,20,16,32), rect(0,20,4,32), rect(8,20,12,32), rect(4,16,8,20), rect(8,16,12,20));
            addPart(skin, 2, 6, 0, 4, 12, 4,
                    rect(20,52,24,64), rect(28,52,32,64), rect(16,52,20,64), rect(24,52,28,64), rect(20,48,24,52), rect(24,48,28,52));
            addArmLayer(skin, -6, armWidth, false, slim);
            addArmLayer(skin, 6, armWidth, true, slim);
            addPart(skin, -2, 6, 0, 4.5, 12.5, 4.5,
                    rect(4,36,8,48), rect(12,36,16,48), rect(0,36,4,48), rect(8,36,12,48), rect(4,32,8,36), rect(8,32,12,36));
            addPart(skin, 2, 6, 0, 4.5, 12.5, 4.5,
                    rect(4,52,8,64), rect(12,52,16,64), rect(0,52,4,64), rect(8,52,12,64), rect(4,48,8,52), rect(8,48,12,52));
        } else {
            int[] front = rect(44,20,48,32), back = rect(52,20,56,32);
            int[] right = rect(40,20,44,32), left = rect(48,20,52,32);
            int[] top = rect(44,16,48,20), bottom = rect(48,16,52,20);
            addPart(skin, -6, -6, 0, 4, 12, 4, front, back, right, left, top, bottom);
            addPart(skin, 6, -6, 0, 4, 12, 4, front, back, left, right, top, bottom);
            addPart(skin, -2, 6, 0, 4, 12, 4,
                    rect(4,20,8,32), rect(12,20,16,32), rect(0,20,4,32), rect(8,20,12,32), rect(4,16,8,20), rect(8,16,12,20));
            addPart(skin, 2, 6, 0, 4, 12, 4,
                    rect(20,20,24,32), rect(28,20,32,32), rect(16,20,20,32), rect(24,20,28,32), rect(20,16,24,20), rect(24,16,28,20));
        }
        rotateX.setAngle(-8); rotateY.setAngle(-22);
    }

    public void setDefaultSkin() {
        WritableImage image = new WritableImage(64, 64);
        var writer = image.getPixelWriter();
        for (int y=0;y<64;y++) for (int x=0;x<64;x++) {
            Color c = y < 16 ? Color.web("#d6a27b") : (y < 40 ? Color.web("#3f6d9c") : Color.web("#26364c"));
            writer.setColor(x,y,c);
        }
        setSkin(image, false);
    }

    private void addArm(Image skin, double centerX, int width, boolean leftArm, boolean slim) {
        int frontX = leftArm ? (slim ? 36 : 36) : 44;
        int backX = leftArm ? 44 : 52;
        int rightX = leftArm ? 32 : 40;
        int leftX = leftArm ? (slim ? 39 : 40) : (slim ? 47 : 48);
        int topX = frontX;
        int bottomX = leftArm ? (slim ? 39 : 40) : (slim ? 47 : 48);
        int faceWidth = width;
        int y1 = leftArm ? 52 : 20;
        int y2 = y1 + 12;
        int topY = leftArm ? 48 : 16;
        addPart(skin, centerX, -6, 0, width, 12, 4,
                rect(frontX,y1,frontX+faceWidth,y2), rect(backX,y1,backX+faceWidth,y2),
                rect(rightX,y1,rightX+4,y2), rect(leftX,y1,leftX+4,y2),
                rect(topX,topY,topX+faceWidth,topY+4), rect(bottomX,topY,bottomX+faceWidth,topY+4));
    }

    private void addArmLayer(Image skin, double centerX, int width, boolean leftArm, boolean slim) {
        int frontX = leftArm ? 52 : 44;
        int backX = leftArm ? 60 : 52;
        int sideRightX = leftArm ? 48 : 40;
        int sideLeftX = leftArm ? 56 : 48;
        int faceWidth = width;
        int y1 = leftArm ? 52 : 36;
        int y2 = y1 + 12;
        int topY = leftArm ? 48 : 32;
        addPart(skin, centerX, -6, 0, width + 0.5, 12.5, 4.5,
                rect(frontX,y1,frontX+faceWidth,y2), rect(backX,y1,backX+faceWidth,y2),
                rect(sideRightX,y1,sideRightX+4,y2), rect(sideLeftX,y1,sideLeftX+4,y2),
                rect(frontX,topY,frontX+faceWidth,topY+4), rect(frontX+faceWidth,topY,frontX+faceWidth*2,topY+4));
    }

    private void installMouseControls() {
        subScene.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> { dragX=e.getSceneX(); dragY=e.getSceneY(); });
        subScene.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            double dx=e.getSceneX()-dragX, dy=e.getSceneY()-dragY;
            rotateY.setAngle(rotateY.getAngle()+dx*0.8);
            rotateX.setAngle(Math.max(-80, Math.min(80, rotateX.getAngle()-dy*0.55)));
            dragX=e.getSceneX(); dragY=e.getSceneY();
        });
        subScene.addEventHandler(ScrollEvent.SCROLL, e -> {
            cameraDistance = Math.max(-190, Math.min(-65, cameraDistance + e.getDeltaY()*0.12));
            camera.setTranslateZ(cameraDistance);
        });
    }

    private static int[] rect(int x1,int y1,int x2,int y2) { return new int[]{x1,y1,x2,y2}; }

    private void addPart(Image skin, double cx, double cy, double cz, double w, double h, double d,
                         int[] front, int[] back, int[] right, int[] left, int[] top, int[] bottom) {
        TriangleMesh mesh = new TriangleMesh();
        float x1=(float)(cx-w/2), x2=(float)(cx+w/2), y1=(float)(cy-h/2), y2=(float)(cy+h/2);
        float zf=(float)(cz-d/2), zb=(float)(cz+d/2);
        addFace(mesh, new float[][]{{x1,y1,zf},{x2,y1,zf},{x2,y2,zf},{x1,y2,zf}}, front, skin);
        addFace(mesh, new float[][]{{x2,y1,zb},{x1,y1,zb},{x1,y2,zb},{x2,y2,zb}}, back, skin);
        addFace(mesh, new float[][]{{x2,y1,zf},{x2,y1,zb},{x2,y2,zb},{x2,y2,zf}}, right, skin);
        addFace(mesh, new float[][]{{x1,y1,zb},{x1,y1,zf},{x1,y2,zf},{x1,y2,zb}}, left, skin);
        addFace(mesh, new float[][]{{x1,y1,zb},{x2,y1,zb},{x2,y1,zf},{x1,y1,zf}}, top, skin);
        addFace(mesh, new float[][]{{x1,y2,zf},{x2,y2,zf},{x2,y2,zb},{x1,y2,zb}}, bottom, skin);
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        PhongMaterial material = new PhongMaterial(Color.WHITE);
        material.setDiffuseMap(skin);
        material.setSpecularColor(Color.color(0.10,0.10,0.10));
        view.setMaterial(material);
        avatar.getChildren().add(view);
    }

    private Image upscaleNearest(Image source) {
        int sourceWidth = (int) source.getWidth();
        int sourceHeight = (int) source.getHeight();
        if (sourceWidth != 64 || sourceHeight != 64) return source;
        int scale = 8;
        WritableImage enlarged = new WritableImage(sourceWidth * scale, sourceHeight * scale);
        PixelReader reader = source.getPixelReader();
        var writer = enlarged.getPixelWriter();
        for (int y = 0; y < sourceHeight * scale; y++) {
            for (int x = 0; x < sourceWidth * scale; x++) {
                writer.setColor(x, y, reader.getColor(x / scale, y / scale));
            }
        }
        return enlarged;
    }

    private void addFace(TriangleMesh mesh, float[][] points, int[] uv, Image image) {
        int basePoint = mesh.getPoints().size()/3;
        int baseTex = mesh.getTexCoords().size()/2;
        for (float[] p : points) mesh.getPoints().addAll(p[0],p[1],p[2]);
        float iw=(float)image.getWidth(), ih=(float)image.getHeight();
        float u1=(uv[0]*textureScale)/iw, v1=(uv[1]*textureScale)/ih, u2=(uv[2]*textureScale)/iw, v2=(uv[3]*textureScale)/ih;
        mesh.getTexCoords().addAll(u1,v1, u2,v1, u2,v2, u1,v2);
        mesh.getFaces().addAll(basePoint,baseTex, basePoint+1,baseTex+1, basePoint+2,baseTex+2,
                basePoint,baseTex, basePoint+2,baseTex+2, basePoint+3,baseTex+3);
    }
}
