package freenet.node;

public class LowLevelPutException extends Exception {
	private static final long serialVersionUID = 1L;
	/** An internal error occurred */
	public static final int INTERNAL_ERROR = 1;
	/** The request could not go enough hops to store the data properly. */
	public static final int ROUTE_NOT_FOUND = 2;
	/** A downstream node is overloaded, and rejected the insert. We should
	 * reduce our rate of sending inserts. */
	public static final int REJECTED_OVERLOAD = 3;
	/** Insert could not get off the node at all */
	public static final int ROUTE_REALLY_NOT_FOUND = 4;
	
	/** Failure code */
	public final int code;
	
	static final String getMessage(int reason) {
		switch(reason) {
		case INTERNAL_ERROR:
			return "Internal error - probably a bug";
		case ROUTE_NOT_FOUND:
			return "Could not store the data on enough nodes";
		case REJECTED_OVERLOAD:
			return "A node downstream either timed out or was overloaded (retry)";
		case ROUTE_REALLY_NOT_FOUND:
			return "The insert could not get off the node at all";
		default:
			return "Unknown error code: "+reason;
		}
		
	}
	
	LowLevelPutException(int code, String message, Throwable t) {
		super(message, t);
		this.code = code;
	}

	LowLevelPutException(int reason) {
		super(getMessage(reason));
		this.code = reason;
	}
	
}
