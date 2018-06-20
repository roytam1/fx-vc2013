/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

this.EXPORTED_SYMBOLS = ["BookmarkSpecialIds", "BookmarkAnnos"];

const { utils: Cu, interfaces: Ci, classes: Cc } = Components;

Cu.import("resource://gre/modules/PlacesUtils.jsm");

let BookmarkAnnos = {
  ALLBOOKMARKS_ANNO:    "AllBookmarks",
  DESCRIPTION_ANNO:     "bookmarkProperties/description",
  SIDEBAR_ANNO:         "bookmarkProperties/loadInSidebar",
  MOBILEROOT_ANNO:      "mobile/bookmarksRoot",
  MOBILE_ANNO:          "MobileBookmarks",
  EXCLUDEBACKUP_ANNO:   "places/excludeFromBackup",
  SMART_BOOKMARKS_ANNO: "Places/SmartBookmark",
  PARENT_ANNO:          "sync/parent",
  ORGANIZERQUERY_ANNO:  "PlacesOrganizer/OrganizerQuery",
};

let BookmarkSpecialIds = {

  // Special IDs. Note that mobile can attempt to create a record on
  // dereference; special accessors are provided to prevent recursion within
  // observers.
  guids: ["menu", "places", "tags", "toolbar", "unfiled", "mobile"],

  // Create the special mobile folder to store mobile bookmarks.
  createMobileRoot: function createMobileRoot() {
    let root = PlacesUtils.placesRootId;
    let mRoot = PlacesUtils.bookmarks.createFolder(root, "mobile", -1);
    PlacesUtils.annotations.setItemAnnotation(
      mRoot, BookmarkAnnos.MOBILEROOT_ANNO, 1, 0, PlacesUtils.annotations.EXPIRE_NEVER);
    PlacesUtils.annotations.setItemAnnotation(
      mRoot, BookmarkAnnos.EXCLUDEBACKUP_ANNO, 1, 0, PlacesUtils.annotations.EXPIRE_NEVER);
    return mRoot;
  },

  findMobileRoot: function findMobileRoot(create) {
    // Use the (one) mobile root if it already exists.
    let root = PlacesUtils.annotations.getItemsWithAnnotation(
      BookmarkAnnos.MOBILEROOT_ANNO, {});
    if (root.length != 0)
      return root[0];

    if (create)
      return this.createMobileRoot();

    return null;
  },

  // Accessors for IDs.
  isSpecialGUID: function isSpecialGUID(g) {
    return this.guids.indexOf(g) != -1;
  },

  specialIdForGUID: function specialIdForGUID(guid, create) {
    if (guid == "mobile") {
      return this.findMobileRoot(create);
    }
    return this[guid];
  },

  // Don't bother creating mobile: if it doesn't exist, this ID can't be it!
  specialGUIDForId: function specialGUIDForId(id) {
    for (let guid of this.guids)
      if (this.specialIdForGUID(guid, false) == id)
        return guid;
    return null;
  },

  get menu() {
    return PlacesUtils.bookmarksMenuFolderId;
  },
  get places() {
    return PlacesUtils.placesRootId;
  },
  get tags() {
    return PlacesUtils.tagsFolderId;
  },
  get toolbar() {
    return PlacesUtils.toolbarFolderId;
  },
  get unfiled() {
    return PlacesUtils.unfiledBookmarksFolderId;
  },
  get mobile() {
    return this.findMobileRoot(true);
  },
};
