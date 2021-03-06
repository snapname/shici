package com.itranswarp.shici.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityNotFoundException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.itranswarp.shici.bean.CategoryBean;
import com.itranswarp.shici.bean.CategoryPoemBean;
import com.itranswarp.shici.bean.CategoryPoemBeans;
import com.itranswarp.shici.bean.FeaturedBean;
import com.itranswarp.shici.bean.PoemBean;
import com.itranswarp.shici.bean.PoetBean;
import com.itranswarp.shici.exception.APIArgumentException;
import com.itranswarp.shici.exception.APIEntityConflictException;
import com.itranswarp.shici.json.LocalDateDeserializer;
import com.itranswarp.shici.json.LocalDateSerializer;
import com.itranswarp.shici.model.BaseEntity;
import com.itranswarp.shici.model.Category;
import com.itranswarp.shici.model.CategoryPoem;
import com.itranswarp.shici.model.Dynasty;
import com.itranswarp.shici.model.FeaturedPoem;
import com.itranswarp.shici.model.Poem;
import com.itranswarp.shici.model.Poet;
import com.itranswarp.shici.model.Resource;
import com.itranswarp.shici.util.HttpUtil;
import com.itranswarp.shici.util.IdUtils;
import com.itranswarp.warpdb.PagedResults;
import com.itranswarp.wxapi.util.MapUtil;

@RestController
@Transactional
public class PoemService extends AbstractService {

	@Autowired
	HanziService hanziService;

	// dynasty ////////////////////////////////////////////////////////////////

	@GetMapping("/api/dynasties")
	public Map<String, List<Dynasty>> restGetDynasties() {
		return MapUtil.createMap("results", getDynasties());
	}

	@GetMapping("/api/dynasties/{id}")
	public Dynasty restGetDynasty(@PathVariable("id") String dynastyId) {
		return getDynasty(dynastyId);
	}

	public List<Dynasty> getDynasties() {
		return warpdb.from(Dynasty.class).orderBy("displayOrder").list();
	}

	public Dynasty getDynasty(String dynastyId) {
		for (Dynasty d : getDynasties()) {
			if (d.id.equals(dynastyId)) {
				return d;
			}
		}
		throw new EntityNotFoundException("Dynasty");
	}

	// poet ///////////////////////////////////////////////////////////////////

	@GetMapping("/api/dynasties/{id}/poets")
	public Map<String, List<Poet>> restGetPoets(@PathVariable("id") String dynastyId) {
		return MapUtil.createMap("results", getPoets(dynastyId));
	}

	public List<Poet> getPoets(String dynastyId) {
		return warpdb.from(Poet.class).where("dynastyId=?", dynastyId).orderBy("name").list();
	}

	@GetMapping("/api/poets/{id}")
	public Poet getPoet(@PathVariable("id") String poetId) {
		return warpdb.get(Poet.class, poetId);
	}

	@PostMapping("/api/poets")
	public Poet createPoet(@RequestBody PoetBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		getDynasty(bean.dynastyId);
		// create:
		Poet poet = new Poet();
		copyToPoet(poet, bean);
		warpdb.save(poet);
		return poet;
	}

	@PostMapping("/api/poets/{id}")
	public Poet updatePoet(@PathVariable("id") String poetId, @RequestBody PoetBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		getDynasty(bean.dynastyId);
		// update:
		Poet poet = getPoet(poetId);
		copyToPoet(poet, bean);
		warpdb.update(poet);
		return poet;
	}

	private void copyToPoet(Poet poet, PoetBean bean) {
		poet.dynastyId = bean.dynastyId;
		poet.name = hanziService.toChs(bean.name);
		poet.nameCht = hanziService.toCht(bean.name);
		poet.description = hanziService.toChs(bean.description);
		poet.descriptionCht = hanziService.toCht(bean.description);
		poet.birth = bean.birth;
		poet.death = bean.death;
	}

	@DeleteMapping("/api/poets/{id}")
	public void deletePoet(@PathVariable("id") String poetId) {
		// check:
		assertEditorRole();
		Poet poet = getPoet(poetId);
		// check:
		if (warpdb.from(Poem.class).where("poetId=?", poetId).limit(1).list().size() > 0) {
			throw new DataIntegrityViolationException("Cannot remove poet who has poems.");
		}
		// delete:
		warpdb.remove(poet);
	}

	// poem ///////////////////////////////////////////////////////////////////

	@GetMapping("/api/poems/{id}")
	public Poem getPoem(@PathVariable("id") String poemId) {
		return warpdb.get(Poem.class, poemId);
	}

	@GetMapping("/api/poets/{id}/poems")
	public PagedResults<Poem> getPoems(@PathVariable("id") String poetId,
			@RequestParam(value = "page", defaultValue = "1") int pageIndex) {
		return warpdb.from(Poem.class).where("poetId=?", poetId).orderBy("name").list(pageIndex, 20);
	}

	@GetMapping("/api/poets/{id}/poems/all")
	public Map<String, List<Poem>> getAllPoems(@PathVariable("id") String poetId) {
		// check:
		assertEditorRole();
		return MapUtil.createMap("results", warpdb.from(Poem.class).where("poetId=?", poetId).orderBy("name").list());
	}

	@PostMapping("/api/poems")
	public Poem createPoem(@RequestBody PoemBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		Poet poet = getPoet(bean.poetId);
		Poem poem = new Poem();
		poem.id = IdUtils.next();
		// create image:
		if (bean.imageData != null) {
			Resource resource = createResource(poem, "cover", ".jpg", bean.imageData);
			poem.imageId = resource.id;
		} else {
			poem.imageId = "";
		}
		// create:
		copyToPoem(poem, poet, bean);
		warpdb.save(poem);
		updatePoemCountOfPoet(bean.poetId);
		return poem;
	}

	@PostMapping("/api/poems/{id}")
	public Poem updatePoem(@PathVariable("id") String poemId, @RequestBody PoemBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		Poet poet = getPoet(bean.poetId);
		Poem poem = getPoem(poemId);
		String oldPoetId = poem.poetId;
		String newPoetId = bean.poetId;
		copyToPoem(poem, poet, bean);
		// update:
		warpdb.update(poem);
		if (!oldPoetId.equals(newPoetId)) {
			updatePoemCountOfPoet(oldPoetId, newPoetId);
		}
		// update image:
		if (bean.imageData != null) {
			String oldResourceId = poem.imageId;
			Resource resource = createResource(poem, "cover", ".jpg", bean.imageData);
			poem.imageId = resource.id;
			warpdb.updateProperties(poem, "imageId");
			if (!oldResourceId.isEmpty()) {
				deleteResource(oldResourceId);
			}
		}
		return poem;
	}

	private void copyToPoem(Poem poem, Poet poet, PoemBean bean) {
		poem.dynastyId = poet.dynastyId;
		poem.poetId = poet.id;
		poem.poetName = poet.name;
		poem.poetNameCht = poet.nameCht;
		poem.form = bean.form;
		poem.tags = bean.tags;
		poem.name = hanziService.toChs(bean.name);
		poem.nameCht = hanziService.toCht(bean.name);
		poem.content = hanziService.toChs(bean.content);
		poem.contentCht = hanziService.toCht(bean.content);
		poem.appreciation = hanziService.toChs(bean.appreciation);
		poem.appreciationCht = hanziService.toCht(bean.appreciation);
	}

	@DeleteMapping("/api/poems/{id}")
	public void deletePoem(@PathVariable("id") String poemId) {
		// check:
		assertEditorRole();
		Poem poem = getPoem(poemId);
		// check if featured or categories:
		if (isFeaturedPoem(poemId)) {
			throw new APIEntityConflictException("Poem", "cannot delete as featured.");
		}
		if (isCategoryPoem(poemId)) {
			throw new APIEntityConflictException("Poem", "cannot delete as categoried.");
		}
		// delete:
		warpdb.remove(poem);
		updatePoemCountOfPoet(poem.poetId);
	}

	private void updatePoemCountOfPoet(String... poetIds) {
		for (String poetId : poetIds) {
			warpdb.update("update Poet set poemCount=(select count(id) from Poem where poetId=?) where id=?", poetId,
					poetId);
		}
	}

	// Resource ///////////////////////////////////////////////////////////////

	public Resource getResource(String resourceId) {
		return warpdb.get(Resource.class, resourceId);
	}

	public Resource createResource(BaseEntity ref, String name, String ext, String base64Data) {
		Resource resource = new Resource();
		resource.meta = "";
		resource.mime = HttpUtil.guessContentType(ext);
		resource.name = name;
		resource.refId = ref.id;
		resource.refType = ref.getClass().getSimpleName();
		resource.size = getSizeOfBase64String(base64Data);
		resource.data = base64Data;
		warpdb.save(resource);
		return resource;
	}

	public void deleteResource(String resourceId) {
		Resource resource = new Resource();
		resource.id = resourceId;
		warpdb.remove(resource);
	}

	int getSizeOfBase64String(String base64Data) {
		int n = base64Data.length();
		if (base64Data.endsWith("==")) {
			n = n - 2;
		} else if (base64Data.endsWith("=")) {
			n = n - 1;
		}
		int seg = (n / 4) * 3;
		int mod = n % 4;
		if (mod == 3) {
			seg = seg + 2;
		} else if (mod == 2) {
			seg = seg + 1;
		}
		return seg;
	}

	// category ///////////////////////////////////////////////////////////////

	@GetMapping("/api/categories")
	public Map<String, List<Category>> restGetCategories() {
		return MapUtil.createMap("results", getCategories());
	}

	@GetMapping("/api/categories/{id}")
	public Category restGetCategory(@PathVariable("id") String categoryId) {
		return getCategory(categoryId);
	}

	public List<Category> getCategories() {
		return warpdb.from(Category.class).orderBy("displayOrder").list();
	}

	public Category getCategory(String categoryId) {
		for (Category c : getCategories()) {
			if (c.id.equals(categoryId)) {
				return c;
			}
		}
		throw new EntityNotFoundException("Category");
	}

	@PostMapping("/api/categories")
	public Category createCategory(@RequestBody CategoryBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		// create:
		Category category = new Category();
		category.name = bean.name;
		category.nameCht = hanziService.toCht(bean.name);
		category.description = bean.description;
		category.descriptionCht = hanziService.toCht(bean.description);
		long max = -1;
		for (Category c : getCategories()) {
			max = c.displayOrder;
		}
		category.displayOrder = max + 1;
		warpdb.save(category);
		return category;
	}

	@PostMapping("/api/categories/{id}")
	public Category updateCategory(@PathVariable("id") String categoryId, @RequestBody CategoryBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		// update:
		Category category = getCategory(categoryId);
		category.name = bean.name;
		category.nameCht = hanziService.toCht(bean.name);
		category.description = bean.description;
		category.descriptionCht = hanziService.toCht(bean.description);
		warpdb.update(category);
		return category;
	}

	@DeleteMapping("/api/categories/{id}")
	public void deleteCategory(@PathVariable("id") String categoryId) {
		// check:
		assertEditorRole();
		Category category = getCategory(categoryId);
		// check if has poem:
		List<CategoryPoem> cps = warpdb.from(CategoryPoem.class).where("categoryId=?", category.id).limit(1).list();
		if (!cps.isEmpty()) {
			throw new APIEntityConflictException("Category", "Cannot remove non-empty category.");
		}
		warpdb.remove(category);
	}

	boolean isCategoryPoem(String poemId) {
		return warpdb.from(CategoryPoem.class).where("poemId=?", poemId).first() != null;
	}

	@GetMapping("/api/categories/{id}/poems")
	public Map<String, List<TheCategoryPoem>> restGetPoemsOfCategory(@PathVariable("id") String categoryId) {
		List<TheCategoryPoem> list = getPoemsOfCategory(categoryId);
		return MapUtil.createMap("results", list);
	}

	public List<TheCategoryPoem> getPoemsOfCategory(String categoryId) {
		Category category = getCategory(categoryId);
		List<CategoryPoem> cps = warpdb.from(CategoryPoem.class).where("categoryId=?", category.id)
				.orderBy("displayOrder").list();
		List<TheCategoryPoem> list = new ArrayList<>();
		if (cps.isEmpty()) {
			return list;
		}
		TheCategoryPoem tcp = null;
		for (CategoryPoem cp : cps) {
			Poem poem = warpdb.get(Poem.class, cp.poemId);
			if (tcp == null) {
				// start new section:
				tcp = new TheCategoryPoem();
				tcp.sectionName = cp.sectionName;
				tcp.sectionNameCht = cp.sectionNameCht;
				tcp.poems = new ArrayList<>(20);
				tcp.poems.add(poem);
			} else {
				if (tcp.sectionName.equals(cp.sectionName)) {
					// continue section:
					tcp.poems.add(poem);
				} else {
					// start new section:
					list.add(tcp);
					tcp = new TheCategoryPoem();
					tcp.sectionName = cp.sectionName;
					tcp.sectionNameCht = cp.sectionNameCht;
					tcp.poems = new ArrayList<>(20);
					tcp.poems.add(poem);
				}
			}
		}
		list.add(tcp);
		return list;
	}

	@PostMapping("/api/categories/{id}/poems")
	public Category updatePoemsOfCategory(@PathVariable("id") String categoryId, @RequestBody CategoryPoemBeans beans) {
		// check:
		assertEditorRole();
		if (beans == null) {
			throw new APIArgumentException("body is empty");
		}
		beans.validate();
		Category category = getCategory(categoryId);
		// set:
		warpdb.update("delete from CategoryPoem where categoryId=?", categoryId);
		long n = 0;
		for (CategoryPoemBean bean : beans.categoryPoems) {
			List<CategoryPoem> list = new ArrayList<>(bean.ids.size());
			for (String poemId : bean.ids) {
				CategoryPoem cp = new CategoryPoem();
				cp.sectionName = bean.sectionName;
				cp.sectionNameCht = hanziService.toCht(bean.sectionName);
				cp.categoryId = categoryId;
				cp.poemId = poemId;
				cp.displayOrder = n;
				list.add(cp);
				n++;
			}
			warpdb.save(list.toArray(new CategoryPoem[list.size()]));
		}
		warpdb.update(category); // update version!
		return category;
	}

	// featured ///////////////////////////////////////////////////////////////

	@GetMapping("/api/featured/poem")
	public Poem restGetFeaturedPoem() {
		return getFeaturedPoem(LocalDate.now());
	}

	public Poem getFeaturedPoem(LocalDate targetDate) {
		FeaturedPoem fp = warpdb.from(FeaturedPoem.class).where("pubDate<=?", targetDate).orderBy("pubDate").desc()
				.first();
		if (fp == null) {
			throw new EntityNotFoundException(Poem.class.getSimpleName());
		}
		return getPoem(fp.poemId);
	}

	@GetMapping("/api/featured/poems")
	public Map<String, List<TheFeaturedPoem>> restGetFeaturedPoems() {
		return MapUtil.createMap("results", getFeaturedPoems());
	}

	public List<TheFeaturedPoem> getFeaturedPoems() {
		List<FeaturedPoem> fps = warpdb.from(FeaturedPoem.class).orderBy("pubDate").desc().list();
		List<TheFeaturedPoem> tfps = new ArrayList<>(fps.size());
		for (FeaturedPoem fp : fps) {
			TheFeaturedPoem tfp = new TheFeaturedPoem();
			tfp.pubDate = fp.pubDate;
			tfp.poem = getPoem(fp.poemId);
			tfps.add(tfp);
		}
		return tfps;
	}

	/**
	 * Add or update a poem as featured.
	 * 
	 * @param bean
	 */
	@PostMapping("/api/featured")
	public FeaturedPoem setPoemAsFeatured(@RequestBody FeaturedBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		Poem poem = getPoem(bean.poemId);
		if (poem.imageId.isEmpty()) {
			throw new APIArgumentException("poemId", "Poem does not have image.");
		}
		FeaturedPoem fp = warpdb.from(FeaturedPoem.class).where("poemId=?", poem.id).first();
		if (fp != null) {
			fp.pubDate = bean.pubDate;
			warpdb.update(fp);
		} else {
			fp = new FeaturedPoem();
			fp.poemId = bean.poemId;
			fp.pubDate = bean.pubDate;
			warpdb.save(fp);
		}
		return fp;
	}

	boolean isFeaturedPoem(String poemId) {
		return warpdb.from(FeaturedPoem.class).where("poemId=?", poemId).first() != null;
	}

	@DeleteMapping("/api/featured/{poemId}")
	public void setPoemAsUnfeatured(@PathVariable("poemId") String poemId) {
		// check:
		assertEditorRole();
		Poem poem = getPoem(poemId);
		FeaturedPoem fp = warpdb.from(FeaturedPoem.class).where("poemId=?", poem.id).first();
		if (fp == null) {
			throw new APIArgumentException("poemId", "Poem is not featured.");
		}
		warpdb.remove(fp);
	}

	public static class TheCategoryPoem {
		public String sectionName;
		public String sectionNameCht;
		public List<Poem> poems;
	}

	public static class TheFeaturedPoem {
		public Poem poem;

		@JsonSerialize(using = LocalDateSerializer.class)
		@JsonDeserialize(using = LocalDateDeserializer.class)
		public LocalDate pubDate;
	}
}
