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

	public Image getLabelImage(ActivityLabel label, int size, boolean outline) {
		// determine the fill color
		RGB rgb = StringConverter.asRGB(label.getColor());
		Display display = Display.getCurrent();
		Color color = new Color(display, rgb.red, rgb.green, rgb.blue);

		// determine dimensions – this can probably be much improved
		int x_offset = (size / 4) + 1; // the +1 is to adjust for the outline
		int y_offset = (size / 4) + 1;
		int diameter = size - (size / 2);

		// create transparent base image
		Image base = new Image(display, size, size);
		ImageData imageData = base.getImageData();
		imageData.setAlpha(0, 0, 0);
		Arrays.fill(imageData.alphaData, (byte) 0);
		base.dispose();

		// create the label image
		Image image = new Image(display, imageData);
		GC gc = new GC(image);
		gc.setLineWidth(0);
		gc.setAntialias(SWT.ON);
		gc.setAdvanced(true);

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
