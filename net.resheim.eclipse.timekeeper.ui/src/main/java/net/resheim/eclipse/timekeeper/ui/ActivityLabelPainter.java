/*******************************************************************************
 * Copyright (c) 2022 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.domain.Label;

/**
 * This type will generate images for {@link ActivityLabel} instances. These are
 * stored in a simple cache which must be disposed of in order to avoid resource
 * leaks. See {@link #disposeImages()}
 *
 * @author Torkild U. Resheim
 * @since 2.0
 */
public class ActivityLabelPainter {

	private static final class ActivityLabelImage {
		private Image image;
		private String lastColor;
	}

	private static HashMap<Object, ActivityLabelImage> images;

	public ActivityLabelPainter() {
		images = new HashMap<>();
	}

	private Object generateKey(final Object... params) {
		final List<Object> key = new ArrayList<>();
		for (final Object o : params) {
			key.add(o);
		}
		return key;
	}

	public Image getLabelImage(ActivityLabel label, int size, boolean outline) {
		return getLabelImage(label, label.getColor(), size, outline);
	}

	public Image getLabelImage(Label label, int size, boolean outline) {
		return getLabelImage(label.id(), label.color().orElse("0,0,0"), size, outline);
	}

	private Image getLabelImage(Object keySource, String labelColor, int size, boolean outline) {

		Object key = generateKey(keySource, Boolean.valueOf(outline));

		if (images.containsKey(key)) {
			ActivityLabelImage activityLabelImage = images.get(key);
			if (activityLabelImage.lastColor.equals(labelColor)) {
				return activityLabelImage.image;
			} else {
				// color has changed, dispose of the old image and continue
				// in order to create a new one
				activityLabelImage.image.dispose();
				images.remove(key);
			}
		}

		// determine the fill color
		RGB rgb = StringConverter.asRGB(labelColor);
		Display display = Display.getCurrent();
		Color color = new Color(display, rgb.red, rgb.green, rgb.blue);

		// determine dimensions – this can probably be much improved
		int x_offset = (size / 4);
		int y_offset = (size / 4) + 1; // the +1 is to adjust for the outline
		int diameter = size - (size / 2);

		// create transparent base image
		Image base = new Image(display, size, size);
		ImageData imageData = base.getImageData();
		imageData.setAlpha(0, 0, 0);
		Arrays.fill(imageData.alphaData, (byte) 0);

		// create the label image using the transparent base
		Image image = new Image(display, imageData);
		GC gc = new GC(image);
		gc.setLineWidth(0);
		gc.setAntialias(SWT.ON);
		gc.setAdvanced(true);

		// fill a circle with the label color
		gc.setBackground(color);
		gc.fillOval(x_offset, y_offset, diameter, diameter);

		// draw outline around the label circle
		if (outline) {
			Color outlineColor = display.getSystemColor(SWT.COLOR_WHITE);
			gc.setForeground(outlineColor);
			gc.drawOval(x_offset - 1, y_offset - 1, diameter + 1, diameter + 1);
		}

		// release resources
		base.dispose();
		gc.dispose();
		color.dispose();

		// store image in cache
		ActivityLabelImage activityImage = new ActivityLabelImage();
		activityImage.image = image;
		activityImage.lastColor = labelColor;
		images.put(key, activityImage);

		return image;
	}

	public void disposeImages() {
		for (ActivityLabelImage image : images.values()) {
			image.image.dispose();
		}
	}

}
