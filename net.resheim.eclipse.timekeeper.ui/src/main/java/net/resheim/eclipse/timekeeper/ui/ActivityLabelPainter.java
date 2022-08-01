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

import java.util.Arrays;

import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;

public class ActivityLabelPainter {

	public Image getLabelImage(ActivityLabel label, int height, boolean outline) {
		// determine the fill color
		RGB rgb = StringConverter.asRGB(label.getColor());
		Display display = Display.getCurrent();
		Color color = new Color(display, rgb.red, rgb.green, rgb.blue);

		// determine dimensions – this can probably be much improved
		int x_offset = 4;
		int y_offset = 4;
		int diameter = height - 6;

		// create transparent base image
		Image base = new Image(display, height + 2, height + 2);
		ImageData imageData = base.getImageData();
		int whitePixel = imageData.palette.getPixel(new RGB(0, 0, 0));
		imageData.transparentPixel = whitePixel;
		imageData.setAlpha(0, 0, 0);
		Arrays.fill(imageData.alphaData, (byte) 0);
		base.dispose();

		// create the label image
		Image image = new Image(display, imageData);
		GC gc = new GC(image);
		gc.setLineWidth(0);
		gc.setAntialias(SWT.OFF);
		gc.setAdvanced(true);
		// black will be transparent
		gc.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
		gc.fillRectangle(image.getBounds());

		// fill a circle with the label color
		gc.setBackground(color);
		gc.fillOval(x_offset, y_offset, diameter, diameter);
		color.dispose();

		// draw outline around the label circle
		if (outline) {
			Color outlineColor = display.getSystemColor(SWT.COLOR_WHITE);
			gc.setForeground(outlineColor);
			gc.drawOval(x_offset - 1, y_offset - 1, diameter + 1, diameter + 1);
		}
		gc.dispose();
		return image;

	}

}
