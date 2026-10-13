/*******************************************************************************
 * Copyright © 2018 Torkild U. Resheim
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.presentation.PresentationReconciler;
import org.eclipse.jface.text.rules.DefaultDamagerRepairer;
import org.eclipse.jface.text.rules.IRule;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.ITokenScanner;
import org.eclipse.jface.text.rules.IWhitespaceDetector;
import org.eclipse.jface.text.rules.RuleBasedScanner;
import org.eclipse.jface.text.rules.SingleLineRule;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.text.rules.WhitespaceRule;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

/**
 * A simple {@link SourceViewerConfiguration} that colours FreeMarker directives
 * and interpolations.
 *
 * @author Torkild U. Resheim
 */
public class FreeMarkerConfiguration extends SourceViewerConfiguration {

	private final Color directiveTokenColor;

	private final Color interpolationTokenColor;

	public FreeMarkerConfiguration() {
		super();
		directiveTokenColor = Display.getDefault().getSystemColor(SWT.COLOR_BLUE);
		interpolationTokenColor = Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED);
	}

	@Override
	public IContentAssistant getContentAssistant(ISourceViewer sourceViewer) {
		ContentAssistant assistant = new ContentAssistant();
		assistant.setInformationControlCreator(getInformationControlCreator(sourceViewer));
		assistant.enableAutoActivation(true);
		assistant.enableAutoInsert(true);
		return assistant;
	}

	@Override
	public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer) {
		PresentationReconciler reconciler = new PresentationReconciler();
		reconciler.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
		DefaultDamagerRepairer dr = new DefaultDamagerRepairer(getTokenScanner());
		reconciler.setDamager(dr, IDocument.DEFAULT_CONTENT_TYPE);
		reconciler.setRepairer(dr, IDocument.DEFAULT_CONTENT_TYPE);
		return reconciler;
	}

	private ITokenScanner getTokenScanner() {
		RuleBasedScanner scanner = new RuleBasedScanner();
		IRule[] rules = new IRule[4];
		IToken directiveToken = new Token(new TextAttribute(directiveTokenColor));
		IToken interpolationToken = new Token(new TextAttribute(interpolationTokenColor));
		rules[0] = new SingleLineRule("<#", ">", directiveToken);
		rules[1] = new SingleLineRule("</#", ">", directiveToken);
		rules[2] = new SingleLineRule("${", "}", interpolationToken);
		rules[3] = new WhitespaceRule(new IWhitespaceDetector() {

			@Override
			public boolean isWhitespace(char c) {
				return Character.isWhitespace(c);
			}
		});
		scanner.setRules(rules);
		return scanner;
	}
}