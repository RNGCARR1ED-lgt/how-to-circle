package com.howtobuild.tools.capability;

import java.util.List;

/**
 * The tool reports dimension values (beyond the measured bounding box) that labels can show.
 */
public interface Dimensionable {
	/** Keys of {@link com.howtobuild.geometry.GeometryResult#values()} worth showing, in display order. */
	List<String> dimensionKeys();
}
