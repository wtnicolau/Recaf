package me.coley.recaf;

import java.io.IOException;

import org.controlsfx.control.Notifications;

import javafx.util.Duration;
import me.coley.event.Bus;
import me.coley.logging.*;
import me.coley.recaf.event.LogEvent;
import me.coley.recaf.util.JavaFX;
import me.coley.recaf.util.Threads;

/**
 * Simple logging to console and file.
 * 
 * @author Matt
 */
public class Logging {
	private final static Logging INSTANCE = new Logging();
	private final static int TRACE_MAX_LEN = 16;
	private Logger<?> lgConsole, lgFile;
	private int indentSize = 3;

	Logging() {
		try {
			lgConsole = new ConsoleLogger(Level.INFO);
			lgFile = new FileLogger("rclog.txt", Level.TRACE);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
	}

	/**
	 * Print a trace-detailed debug message.
	 * 
	 * @param message
	 *            Message to print.
	 */
	public static void trace(String message) {
		INSTANCE.log(Level.TRACE, message);
	}

	/**
	 * Print a trace-detailed debug message with an indent level pre-pended.
	 * 
	 * @param message
	 *            Message to print.
	 * @param indent
	 *            Level of indentation.
	 */
	public static void trace(String message, int indent) {
		String formatted = pad(message, (indent * INSTANCE.indentSize), ' ');
		INSTANCE.log(Level.TRACE, formatted);
	}

	/**
	 * Print a finely-detailed informational message.
	 * 
	 * @param message
	 *            Message to print.
	 */
	public static void fine(String message) {
		INSTANCE.log(Level.FINE, message);
	}

	/**
	 * Print a finely-detailed informational message with an indent level
	 * pre-pended.
	 * 
	 * @param message
	 *            Message to print.
	 * @param indent
	 *            Level of indentation.
	 */
	public static void fine(String message, int indent) {
		String formatted = pad(message, (indent * INSTANCE.indentSize), ' ');
		INSTANCE.log(Level.FINE, formatted);
	}

	/**
	 * Print an informational message.
	 * 
	 * @param message
	 *            Message to print.
	 */
	public static void info(String message) {
		INSTANCE.log(Level.INFO, message);
	}

	/**
	 * Print an informational message with an indent level pre-pended.
	 * 
	 * @param message
	 *            Message to print.
	 * @param indent
	 *            Level of indentation.
	 */
	public static void info(String message, int indent) {
		String formatted = pad(message, (indent * INSTANCE.indentSize), ' ');
		INSTANCE.log(Level.INFO, formatted);
	}

	/**
	 * Print an exception as a warning.
	 * 
	 * @param exception
	 *            Exception to print.
	 */
	public static void warn(Exception exception) {
		String message = getErrorMessage(exception);
		Logging.warn(message);
	}

	/**
	 * Print a warning message.
	 * 
	 * @param message
	 *            Message to print.
	 */
	public static void warn(String message) {
		INSTANCE.log(Level.WARN, message);
	}

	/**
	 * Print an error message.
	 * 
	 * @param message
	 *            Message to print.
	 */
	public static void error(String message) {
		INSTANCE.log(Level.ERRR, message);
	}

	/**
	 * Print an exception and displays it in the UI.
	 * 
	 * @param exception
	 *            Exception to print.
	 */
	public static void error(Exception exception) {
		Logging.error(exception, true, false);
	}

	/**
	 * Print an exception.
	 * 
	 * @param exception
	 *            Exception to print.
	 * @param display
	 *            Show error in UI.
	 */
	public static void error(Exception exception, boolean display) {
		Logging.error(exception, display, false);
	}

	/**
	 * Print an error message and optionally display it.
	 * 
	 * @param message
	 *            Message to print.
	 * @param display
	 *            Show error in UI.
	 */
	public static void error(String message, boolean display) {
		Logging.error(message);
		if (display && JavaFX.isToolkitLoaded()) {
			//@formatter:off
			String title = message;
			if (title.contains("\n")) {
				title = title.substring(0, title.indexOf("\n")) + "...";
			}
			if (title.length() > 80) {
				title = title.substring(0, 77) + "...";
			}
			Notifications.create()
		        .title("Error: " + title)
		        .text(message)
		        .hideAfter(Duration.seconds(5))
		        .showError();
			//@formatter:on
		}
	}

	/**
	 * Print an exception.
	 * 
	 * @param exception
	 *            Exception to print.
	 * @param display
	 *            Show error in UI.
	 * @param terminate
	 *            Stop program after printing.
	 */
	public static void error(Exception exception, boolean display, boolean terminate) {
		String message = getErrorMessage(exception);
		Logging.error(message);
		if (display && JavaFX.isToolkitLoaded()) {
			try {
			//@formatter:off
			Threads.runLaterFx(0, () -> {
				Notifications.create()
		        .title("Error: " + exception.getClass().getSimpleName())
		        .text(message)
		        .hideAfter(Duration.seconds(5))
		        .showError();
			});
			//@formatter:on
			} catch (Exception e) {}
		}
		if (terminate) {
			System.exit(0);
		}
	}

	/**
	 * Print an exception and terminate the program.
	 * 
	 * @param exception
	 *            Exception to print.
	 */
	public static void fatal(Exception exception) {
		Logging.error(exception, true, true);
	}

	/**
	 * Print the message of the given level.
	 * 
	 * @param level
	 *            Detail level.
	 * @param message
	 *            Message to print.
	 */
	private void log(Level level, String message) {
		if (message != null) {
			lgConsole.log(level, message);
			lgFile.log(level, message);
			if (JavaFX.isToolkitLoaded()) {
				Threads.runFx(() -> Bus.post(new LogEvent(level, message)));
			}
		}
	}

	/**
	 * Set the console's logging level.
	 * 
	 * @param level
	 *            Detail level.
	 */
	public static void setLevelConsole(Level level) {
		Logging.setLevel(INSTANCE.lgConsole, level);
	}

	/**
	 * Set the file's logging level.
	 * 
	 * @param level
	 *            Detail level.
	 */
	public static void setLevelFile(Level level) {
		Logging.setLevel(INSTANCE.lgFile, level);
	}

	/**
	 * Set the logger's level to the given one.
	 * 
	 * @param logger
	 * @param level
	 */
	private static void setLevel(Logger<?> logger, Level level) {
		logger.setLevel(level);
	}

	/**
	 * Pad the given message with the given amount of characters.
	 * 
	 * @param message
	 * @param padding
	 * @param padChar
	 * @return Message with prepended padding.
	 */
	private static String pad(String message, int padding, char padChar) {
		StringBuilder padded = new StringBuilder(message);
		for (int i = 0; i < padding; i++) {
			padded.insert(0, padChar);
		}
		return padded.toString();
	}

	/**
	 * Convert exception to string.
	 * 
	 * @param exception
	 *            Exception to convert.
	 * @return Formatted string containing the type, message, and trace.
	 */
	private static String getErrorMessage(Exception exception) {
		StringBuilder message = new StringBuilder(exception.getClass().getSimpleName() + ": " + exception.getMessage() + "\n");
		appendThrowable(message, exception, 1);
		for (Throwable suppressed : exception.getSuppressed()) {
			message.append(pad("Suppressed: " + suppressed.getClass().getSimpleName() + ": " + suppressed.getMessage() + "\n",
					INSTANCE.indentSize, ' '));
			appendThrowable(message, suppressed, 2);
		}
		Throwable cause = exception.getCause();
		if (cause != null) {
			message.append(pad("Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "\n",
					INSTANCE.indentSize, ' '));
			appendThrowable(message, cause, 1);
		}
		return message.toString();
	}

	/**
	 * @param builder
	 *            StringBuilder to append to.
	 * @param t
	 *            Throwable to log.
	 * @param indent
	 *            Level of indentation.
	 */
	private static void appendThrowable(StringBuilder builder, Throwable t, int indent) {
		int trace = 0;
		for (StackTraceElement element : t.getStackTrace()) {
			String formatted = pad(element.toString(), INSTANCE.indentSize * indent, ' ');
			builder.append(formatted).append("\n");
			trace++;
			if (trace >= TRACE_MAX_LEN) break;
		}
	}

}