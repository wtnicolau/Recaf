package me.coley.recaf.ui.component;

import javafx.scene.layout.HBox;

/**
 * HBox that is externally updated to keep track of the text content of child
 * nodes.
 * 
 * @author Matt
 */
public class HTextBox extends HBox {
	private final StringBuilder content = new StringBuilder();

	public void append(String s) {
		content.append(s);
	}

	public String getText() {
		return content.toString();
	}
}
