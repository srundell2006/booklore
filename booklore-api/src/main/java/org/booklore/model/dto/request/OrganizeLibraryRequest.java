package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrganizeLibraryRequest {

    /** The library to organize. Required. */
    private Long libraryId;
}
