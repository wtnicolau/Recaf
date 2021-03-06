package me.coley.recaf.ui.component.editor;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import org.controlsfx.control.PropertySheet.Item;
import org.objectweb.asm.*;
import javafx.scene.Node;
import me.coley.recaf.ui.component.*;
import me.coley.recaf.ui.component.ReflectivePropertySheet.CustomEditor;
import me.coley.recaf.util.Reflect;

/**
 * Editor for asm Type fields.
 * 
 * @author Matt
 *
 * @param <T>
 *            {@code Type}
 */
public abstract class TypeFromIndyEditor extends CustomEditor<Type> {
	public TypeFromIndyEditor(Item item) {
		super(item);
	}

	@Override
	public Node getEditor() {
		Object[] array = Reflect.get(item.getOwner(), item.getField());
		Type original = (Type) array[getIndex()];
		return new ReflectiveTextField<Type>(item.getOwner(), item.getField()) {

			protected void setText(Object instance, Field field) {
				this.setText(convert(original));
			}

			@Override
			protected Type convert(String text) {
				// return null by default, in case any parsing errors
				// occur
				Type t = null;
				try {
					// return null if the types do not match the
					// original
					t = Type.getType(text);
					if (t == null || !match(t, original)) {
						return null;
					}
				} catch (Exception e) {}
				return t;
			}

			@Override
			protected String convert(Type text) {
				return text.getDescriptor();
			}

			@Override
			protected void set(Object instance, Field field, Type converted) {
				Array.set(array, getIndex(), converted);
			}

			private boolean match(Type t, Type original) {
				// Pretty sure as long as the kind of type matches
				// (non-methods can be grouped together) it'll be fine.
				return original.getSort() == Type.METHOD && original.getSort() == t.getSort();
			}

		};
	}

	protected abstract int getIndex();
}