/* 
 * =============================================================
 * Copyright (C) 2007-2011 Edgenius (http://www.edgenius.com)
 * =============================================================
 * License Information: http://www.edgenius.com/licensing/edgenius/2.0/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2.0
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 * http://www.gnu.org/licenses/gpl.txt
 *  
 * ****************************************************************
 */
package com.edgenius.wiki.webapp.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.edgenius.core.Constants;
import com.edgenius.core.repository.FileNode;
import com.edgenius.core.repository.RepositoryQuotaException;
import com.edgenius.core.repository.RepositoryService;
import com.edgenius.core.service.MessageService;
import com.edgenius.core.service.UserReadingService;
import com.edgenius.core.util.FileUtil;
import com.edgenius.core.webapp.BaseServlet;
import com.edgenius.core.webapp.filter.AjaxRedirectFilter.RedirectResponseWrapper;
import com.edgenius.wiki.WikiConstants;
import com.edgenius.wiki.gwt.client.server.constant.PageType;
import com.edgenius.wiki.service.ActivityLogService;
import com.edgenius.wiki.service.PageException;
import com.edgenius.wiki.service.PageService;
import com.edgenius.wiki.util.WikiUtil;

/**
 * @author Dapeng.Ni
 */
@SuppressWarnings("serial")
public class UploadServlet extends BaseServlet {
	private static final Logger log = LoggerFactory.getLogger(UploadServlet.class);

	@SuppressWarnings("unchecked")
	protected void doService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException{
		
		
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            //just render blank page for upload
            String pageUuid = request.getParameter("puuid");
            String spaceUname = request.getParameter("uname");
            String draft = request.getParameter("draft");

            request.setAttribute("pageUuid", pageUuid);
            request.setAttribute("spaceUname", spaceUname);
            request.setAttribute("draft", NumberUtils.toInt(draft, PageType.NONE_DRAFT.value()));

            request.getRequestDispatcher("/WEB-INF/pages/upload.jsp").forward(request, response);

            return;
        }

		//post - upload
		
//		if(WikiUtil.getUser().isAnonymous()){
//		//anonymous can not allow to upload any files

		PageService pageService = getPageService();

		ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());

		List<FileNode> files = new ArrayList<FileNode>();
		String pageUuid = null, spaceUname = null;
		try {
			int status = PageType.NONE_DRAFT.value();
			// index->filename
			Map<String, FileItem> fileMap = new HashMap<String, FileItem>();
			Map<String, String> descMap = new HashMap<String, String>();
			// index->index
			Map<String, String> indexMap = new HashMap<String, String>();
			
			//offline submission, filename put into hidden variable rather than <input type="file> tag
			Map<String, String> filenameMap = new HashMap<String, String>();
			//TODO: offline submission, version also upload together with file, this give a change to do failure tolerance check:
			//if version is same with online save, then it is OK, if greater, means it maybe duplicated upload, if less, unpexected case
			Map<String, String> versionMap = new HashMap<String, String>();
			
			Map<String, Boolean> bulkMap = new HashMap<String, Boolean>();
			
			Map<String, Boolean> sharedMap = new HashMap<String, Boolean>();
			List<FileItem> items = upload.parseRequest(request);
			for (FileItem item : items) {
				String name = item.getFieldName();
				if (StringUtils.equals(name, "spaceUname")) {
					spaceUname = item.getString(Constants.UTF8);
				} else if (StringUtils.equals(name, "pageUuid")) {
					pageUuid = item.getString();
				} else if (name.startsWith("draft")) {
					// check this upload is from "click save button" or "auto upload in draft status"
					status = Integer.parseInt(item.getString());
				} else if (name.startsWith("file")) {
					fileMap.put(name.substring(4), item);
					indexMap.put(name.substring(4), name.substring(4));
				} else if (name.startsWith("desc")) {
					descMap.put(name.substring(4), item.getString(Constants.UTF8));
				} else if (name.startsWith("shar")) {
					sharedMap.put(name.substring(4), Boolean.parseBoolean(item.getString()));
				} else if (name.startsWith("name")) {
					filenameMap.put(name.substring(4),item.getString());
				} else if (name.startsWith("vers")) {
					versionMap.put(name.substring(4),item.getString());
				} else if (name.startsWith("bulk")) {
					bulkMap.put(name.substring(4),BooleanUtils.toBoolean(item.getString()));
				}
			}
			if(StringUtils.isBlank(pageUuid)){
				log.error("Attachment can not be load because of page does not save successfully.");
				throw new PageException("Attachment can not be load because of page does not save successfully.");
			}
			
			List<FileNode> bulkFiles = new ArrayList<FileNode>();
			String username = request.getRemoteUser();
			// put file/desc pair into final Map
			for (String id : fileMap.keySet()) {
				FileItem item = fileMap.get(id);
				if (item == null || item.getInputStream() == null || item.getSize() <= 0) {
					log.warn("Empty upload item:" + (item != null ? item.getName() : ""));
					continue;
				}
				FileNode node = new FileNode();
				node.setComment(descMap.get(id));
				node.setShared(sharedMap.get(id) == null ? false : sharedMap.get(id));
				node.setFile(item.getInputStream());
				String filename = item.getName();
				if(StringUtils.isBlank(filename)){
					//this could be offline submission, get name from map
					filename = filenameMap.get(id);
				}
				node.setFilename(FileUtil.getFileName(filename));
				node.setContentType(item.getContentType());
				node.setIndex(indexMap.get(id));
				node.setType(RepositoryService.TYPE_ATTACHMENT);
				node.setIdentifier(pageUuid);
				node.setCreateor(username);
				node.setStatus(status);
				node.setSize(item.getSize());
				node.setBulkZip(bulkMap.get(id) == null?false:bulkMap.get(id));
				
				files.add(node);
				
				if(node.isBulkZip())
					bulkFiles.add(node);
			}
			if (spaceUname != null && pageUuid != null && files.size() > 0) {
				files = pageService.uploadAttachments(spaceUname, pageUuid, files, false);
				
				//only save non-draft uploaded attachment
				if(status == 0){
					try {
						getActivityLog().logAttachmentUploaded(spaceUname, pageService.getCurrentPageByUuid(pageUuid).getTitle(), WikiUtil.getUser(), files);
					} catch (Exception e) {
						log.warn("Activity log save error for attachment upload",e);
					}
				}
				//as bulk files won't in return list in PageService.uploadAttachments(), here need 
				//append to all return list, but only for client side "uploading panel" clean purpose
				files.addAll(bulkFiles);
				//TODO: if version come in together, then do check
//				if(versionMap.size() > 0){
//					for (FileNode node: files) {
//						
//					}
//				}
			}
			
		} catch (RepositoryQuotaException e) {
			FileNode att = new FileNode();
			att.setError(getMessageService().getMessage("err.quota.exhaust"));
			files = Arrays.asList(att);
		} catch (AuthenticationException e) {
			String redir = ((RedirectResponseWrapper)response).getRedirect();
			if(redir == null) redir = WikiConstants.URL_LOGIN;
			log.info("Send Authentication redirect URL " + redir);
			
			FileNode att = new FileNode();
            att.setError(getMessageService().getMessage("err.authentication.required"));
            files = Arrays.asList(att);

		} catch (AccessDeniedException e) {
			String redir = ((RedirectResponseWrapper)response).getRedirect();
			if(redir == null) redir = WikiConstants.URL_ACCESS_DENIED;
			log.info("Send AccessDenied redirect URL " + redir);
			
	        FileNode att = new FileNode();
	        att.setError(getMessageService().getMessage("err.access.denied"));
            files = Arrays.asList(att);

		} catch (Exception e) {
			// FileUploadException,RepositoryException
			log.error("File upload failed " , e);
			FileNode att = new FileNode();
			att.setError(getMessageService().getMessage("err.upload"));
			files = Arrays.asList(att);
		}

		try {
			String json = FileNode.toAttachmentsJson(files,spaceUname, WikiUtil.getUser(), getMessageService(), getUserReadingService());
			
			//TODO: does not compress request in Gzip, refer to 
			//http://www.google.com/codesearch?hl=en&q=+RemoteServiceServlet+show:PAbNFg2Qpdo:akEoB_bGF1c:4aNSrXYgYQ4&sa=N&cd=1&ct=rc&cs_p=https://ssl.shinobun.org/svn/repos/trunk&cs_f=proprietary/gwt/gwt-user/src/main/java/com/google/gwt/user/server/rpc/RemoteServiceServlet.java#first
			byte[] reply = json.getBytes(Constants.UTF8);
			response.setContentLength(reply.length);
			response.setContentType("text/plain; charset=utf-8");
			response.getOutputStream().write(reply);
		} catch (IOException e) {
			log.error(e.toString(),e);
		}
	}



	private MessageService getMessageService() {
		ServletContext context = this.getServletContext();
		ApplicationContext ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(context);
		return (MessageService) ctx.getBean(MessageService.SERVICE_NAME);
	}
	
	private UserReadingService getUserReadingService() {
	    ServletContext context = this.getServletContext();
	    ApplicationContext ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(context);
	    return (UserReadingService) ctx.getBean(UserReadingService.SERVICE_NAME);
	}
	private PageService getPageService() {
		ServletContext context = this.getServletContext();
		ApplicationContext ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(context);
		return (PageService) ctx.getBean(PageService.SERVICE_NAME);
	}
	private ActivityLogService getActivityLog() {
		ServletContext context = this.getServletContext();
		ApplicationContext ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(context);
		return (ActivityLogService) ctx.getBean(ActivityLogService.SERVICE_NAME);
	}

}
